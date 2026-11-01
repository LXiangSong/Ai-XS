"""One visible conversation must survive Agent changes, reloads and failures."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from shared_conversations import SharedConversations, timestamp_seconds


class SharedConversationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.native={};self.calls=[];self.n=0
        self.store=self.make_store()

    def tearDown(self):self.tmp.cleanup()

    def make_store(self):
        return SharedConversations(self.root/'chats',self.call,self.read_native,
            lambda args:{'threadId':args['threadId'],'tokenUsage':{'total':{'inputTokens':10}}},lambda *args:'ask')

    def read_native(self,params):return {'thread':copy.deepcopy(self.native[params['threadId']])}

    def call(self,method,params):
        self.calls.append((method,copy.deepcopy(params)))
        if method=='thread/start':
            self.n+=1;engine=params['engine'];tid=(engine+'-' if engine!='codex' else '')+str(self.n)
            self.native[tid]={'id':tid,'engine':engine,'cwd':str(self.root),'model':'test','turns':[]}
            return {'thread':copy.deepcopy(self.native[tid])}
        if method in ('thread/resume','native/permissions/set'):return {}
        if method=='thread/delete':self.native.pop(params['threadId'],None);return {}
        if method=='turn/start':
            tid=params['threadId'];data=self.native[tid];engine=data['engine']
            turn={'id':'turn-'+str(len(data['turns'])),'status':'inProgress','items':[
                {'id':'user','type':'userMessage','content':params['input']}]}
            self.store.translate({'type':'notification','method':'turn/started','params':{'threadId':tid,'turn':turn}},True)
            command={'id':'tool','type':'commandExecution','command':'pwd','aggregatedOutput':str(self.root),'status':'completed'}
            self.store.translate({'type':'notification','method':'item/completed','params':{'threadId':tid,'turnId':turn['id'],'item':command}},True)
            self.store.translate({'type':'notification','method':'item/agentMessage/delta','params':{'threadId':tid,'turnId':turn['id'],'itemId':'answer','delta':engine+' answer'}},True)
            turn['status']='completed';turn['items'].extend([command,{'id':'answer','type':'agentMessage','text':engine+' answer'}])
            data['turns'].append(copy.deepcopy(turn))
            self.store.translate({'type':'notification','method':'turn/completed','params':{'threadId':tid,'turn':turn}},True)
            return {'turn':turn}
        return {}

    def start(self,engine='deepseek'):
        return self.store.handle('thread/start',{'engine':engine,'cwd':str(self.root),'model':'test'})['thread']['id']

    def send(self,ident,engine,prompt):
        return self.store.handle('turn/start',{'engine':engine,'threadId':ident,'model':'test','input':[{'type':'text','text':prompt}]})

    def test_three_agents_continue_same_id_with_history_and_tools_in_context(self):
        ident=self.start()
        self.send(ident,'deepseek','remember violet-731 and keep the project files')
        for engine in ('codex','claude','deepseek'):
            count=len(self.store.adopt(ident)['turns'])
            before=len(self.calls)
            view=self.store.handle('switch',{'threadId':ident,'engine':engine,'model':'test'})['thread']
            self.assertEqual(view['id'],ident);self.assertEqual(len(view['turns']),count)
            self.assertEqual(before,len(self.calls),'Switching must not launch an Agent')
            self.send(ident,engine,'continue without repeating commands')
            launch=next(params for method,params in reversed(self.calls) if method=='thread/start')
            self.assertIn('violet-731',launch['developerInstructions'])
            self.assertIn('deepseek answer',launch['developerInstructions'])
            self.assertIn('pwd',launch['developerInstructions'])
            self.assertIn(str(self.root),launch['developerInstructions'])
        doc=self.store.handle('thread/read',{'threadId':ident})['thread']
        self.assertEqual([turn['engine'] for turn in doc['turns']],['deepseek','codex','claude','deepseek'])
        self.assertEqual(len({turn['id'] for turn in doc['turns']}),4,'Native turn IDs can collide')
        rows=self.store.listing([{'id':key} for key in self.native],False)
        self.assertEqual([row['id'] for row in rows],[ident],'Native child sessions must not duplicate the conversation')
        restored=self.make_store().handle('thread/read',{'threadId':ident})['thread']
        self.assertEqual(restored['turns'],doc['turns']);self.assertEqual(restored['engine'],'deepseek')

    def test_streamed_reasoning_survives_reading_during_a_live_turn(self):
        ident=self.start('claude');self.send(ident,'claude','one');doc=self.store.adopt(ident)
        source=doc['binding']['threadId']
        def event(method, **params):
            return self.store.translate({'type':'notification','method':method,'params':{'threadId':source,**params}},True)
        event('turn/started',turn={'id':'live','status':'inProgress','items':[]})
        event('item/started',turnId='live',item={'id':'thought','type':'reasoning','content':[],'status':'inProgress'})
        event('item/reasoning/textDelta',turnId='live',itemId='thought',contentIndex=0,delta='Inspect')
        event('item/reasoning/textDelta',turnId='live',itemId='thought',contentIndex=0,delta=' files')
        event('item/reasoning/summaryTextDelta',turnId='live',itemId='thought',summaryIndex=1,delta='Plan')
        # A later tool status persists the live turn before any final thought arrives.
        event('item/started',turnId='live',item={'id':'read','type':'dynamicToolCall','tool':'Read','status':'inProgress'})
        live=self.store.view(doc)['turns'][-1]
        self.assertEqual(live['status'],'inProgress')
        thought=next(i for i in live['items'] if i['id']=='thought')
        self.assertEqual(thought['content'],['Inspect files'])
        self.assertEqual(thought['summary'],['','Plan'])
        saved=json.loads(self.store._path(ident).read_text())['turns'][-1]['items'][0]
        self.assertEqual(saved['content'],['Inspect files'])

    def test_same_agent_reuses_native_context_and_permission_is_scoped(self):
        ident=self.start('codex');self.send(ident,'codex','one');self.send(ident,'codex','two')
        self.assertEqual(sum(method=='thread/start' for method,_ in self.calls),1)
        self.store.permissions('permissions/set',{'threadId':ident,'engine':'codex','mode':'auto'})
        self.store.handle('switch',{'threadId':ident,'engine':'claude'})
        self.assertEqual(self.store.permissions('permissions/read',{'threadId':ident,'engine':'claude'})['mode'],'ask')
        self.store.handle('switch',{'threadId':ident,'engine':'codex'})
        self.send(ident,'codex','three')
        modes=[args['mode'] for method,args in self.calls if method=='native/permissions/set']
        self.assertEqual(modes,['ask','ask','auto'])

    def test_native_null_timestamps_do_not_drop_final_turn_or_live_duration(self):
        ident=self.start('codex');self.send(ident,'codex','one')
        source=self.store.adopt(ident)['binding']['threadId']
        def event(method,turn):
            return self.store.translate({'method':method,'params':{'threadId':source,'turn':turn}},True)['params']['turn']
        start=event('turn/started',{'id':'nullable','status':'inProgress','startedAt':None,'completedAt':None,'items':[]})
        self.assertIsInstance(start['startedAt'],(int,float))
        final=event('turn/completed',{'id':'nullable','status':'completed','startedAt':None,'completedAt':None,'durationMs':None,
            'items':[{'id':'answer','type':'agentMessage','text':'Done.'}]})
        self.assertEqual(final['startedAt'],start['startedAt'])
        self.assertIsInstance(final['completedAt'],(int,float));self.assertGreaterEqual(final['durationMs'],0)
        restored=self.make_store().handle('thread/read',{'threadId':ident})['thread']['turns'][-1]
        self.assertEqual(restored['status'],'completed');self.assertEqual(restored['items'][0]['text'],'Done.')

    def test_native_timestamps_normalize_units_and_preserve_exact_duration(self):
        for stamp in (None,0,False,float('nan'),'invalid'):self.assertIsNone(timestamp_seconds(stamp))
        self.assertEqual(timestamp_seconds(1800000000123),1800000000.123)
        self.assertEqual(timestamp_seconds('2027-01-15T08:00:00Z'),1800000000)
        ident=self.start('codex');self.send(ident,'codex','one');source=self.store.adopt(ident)['binding']['threadId']
        def event(method,**fields):
            return self.store.translate({'method':method,'params':{'threadId':source,'turn':dict(id='timed',items=[],**fields)}},True)['params']['turn']
        event('turn/started',status='inProgress',startedAt=1800000000123,completedAt=0)
        final=event('turn/completed',status='completed',startedAt=0,completedAt=1800000002623,durationMs=None)
        self.assertEqual(final['durationMs'],2500);self.assertEqual(final['startedAt'],1800000000.123)
        final=event('turn/completed',status='completed',completedAt=1800000003000,durationMs=2601)
        self.assertEqual(final['durationMs'],2601)

    def test_import_rename_fork_archive_delete_removes_native_and_retains_fork(self):
        self.native['deepseek-old']={'id':'deepseek-old','cwd':str(self.root),'model':'test','preview':'legacy','turns':[
            {'id':'old','status':'completed','items':[{'id':'user','type':'userMessage','content':[{'type':'text','text':'old words'}]}]}]}
        doc=self.store.handle('thread/read',{'threadId':'deepseek-old'})['thread']
        self.assertEqual(doc['turns'][0]['engine'],'deepseek')
        self.store.handle('thread/name/set',{'threadId':doc['id'],'name':'shared title'})
        fork=self.store.handle('thread/fork',{'threadId':doc['id']})['thread']
        self.assertNotEqual(fork['id'],doc['id']);self.assertEqual(fork['turns'],doc['turns'])
        self.store.handle('thread/archive',{'threadId':doc['id']})
        self.assertEqual(self.store.listing([],True)[0]['name'],'shared title')
        self.store.handle('thread/delete',{'threadId':doc['id']})
        self.assertNotIn('deepseek-old',self.native)
        self.assertFalse(self.store._path(doc['id']).exists())
        self.assertEqual(self.store.adopt(fork['id'])['turns'],fork['turns'])
        self.assertNotIn('preview',self.store.index[doc['id']])
        self.assertFalse(any(row['id']==doc['id'] for row in self.store.listing([{'id':'deepseek-old'}],False)))

    def test_inflight_switch_rejected_and_failed_launch_retains_history(self):
        ident=self.start();self.send(ident,'deepseek','saved words');doc=self.store.adopt(ident)
        doc['turns'][-1]['status']='inProgress'
        with self.assertRaisesRegex(ValueError,'停止当前任务'):self.store.handle('switch',{'threadId':ident,'engine':'codex'})
        doc['turns'][-1]['status']='completed'
        self.store.handle('switch',{'threadId':ident,'engine':'codex'})
        before=copy.deepcopy(doc['turns']);original=self.store.call
        self.store.call=lambda *args:(_ for _ in ()).throw(RuntimeError('missing Codex executable'))
        with self.assertRaisesRegex(RuntimeError,'missing Codex'):self.send(ident,'codex','next')
        self.assertEqual(doc['turns'],before);self.assertNotIn(ident,self.store.busy)
        self.store.call=original

    def test_handoff_limits_prompt_but_keeps_full_history_and_attachment_reference(self):
        ident=self.start();doc=self.store.adopt(ident)
        text='opening requirement '+('x'*140000)+' ending task'
        doc['turns']=[{'id':'long','engine':'deepseek','status':'completed','items':[
            {'id':'user','type':'userMessage','content':[{'type':'text','text':text},{'type':'image','url':'/root/photo.png'}]}]}]
        context,clipped=self.store.handoff(doc)
        self.assertTrue(clipped);self.assertLess(len(context),122000)
        self.assertIn('opening requirement',context);self.assertIn('ending task',context)
        self.assertIn('/root/photo.png',context)
        self.assertEqual(doc['turns'][0]['items'][0]['content'][0]['text'],text)

    def edit(self, ident, turn, text, engine='deepseek', item='user'):
        return self.store.handle('turn/edit',{'threadId':ident,'engine':engine,'turnId':turn['id'],
            'itemId':item,'text':text,'model':'test'})

    def test_edit_routes_each_agent_with_retained_context_and_intact_backup(self):
        for engine in ('deepseek','claude','codex'):
            with self.subTest(engine=engine):
                ident=self.start();self.send(ident,'deepseek','earlier context')
                self.send(ident,'deepseek','superseded prompt');self.send(ident,'deepseek','future prompt')
                doc=self.store.adopt(ident);target=copy.deepcopy(doc['turns'][1])
                attachment={'type':'image','url':'/root/photo.png'}
                doc['turns'][1]['items'][0]['content'].append(attachment)
                self.store.handle('switch',{'threadId':ident,'engine':engine})
                before=copy.deepcopy(doc['turns']);count=len(self.calls)
                result=self.edit(ident,target,'replacement',engine)
                self.assertEqual(result['thread']['id'],ident)
                self.assertEqual(len(result['thread']['turns']),2)
                self.assertEqual(result['turn']['engine'],engine)
                calls=self.calls[count:];launch=next(p for m,p in calls if m=='thread/start')
                self.assertIn('earlier context',launch['developerInstructions'])
                self.assertNotIn('superseded prompt',launch['developerInstructions'])
                self.assertNotIn('future prompt',launch['developerInstructions'])
                self.assertFalse(any(m=='thread/resume' for m,p in calls))
                sent=next(p for m,p in calls if m=='turn/start')
                self.assertEqual(sent['input'],[{'type':'text','text':'replacement'},attachment])
                backup=self.store.handle('thread/read',{'threadId':result['editBackupThreadId']})['thread']
                self.assertEqual(backup['turns'],before);self.assertIn('编辑前',backup['name'])
                self.assertEqual(self.make_store().adopt(ident)['turns'],result['thread']['turns'])

    def test_edit_exact_ids_validation_and_launch_failure_are_atomic(self):
        ident=self.start();self.send(ident,'deepseek','same');self.send(ident,'deepseek','same')
        doc=self.store.adopt(ident);before=copy.deepcopy(doc);target=doc['turns'][1]
        for text,item in [('', 'user'),('new','answer'),('new','missing')]:
            with self.assertRaises(ValueError):self.edit(ident,target,text,item=item)
            self.assertEqual(doc,before)
        doc['turns'][-1]['status']='inProgress'
        with self.assertRaisesRegex(ValueError,'停止当前任务'):self.edit(ident,target,'new')
        doc['turns'][-1]['status']='completed'
        self.store.call=lambda *args:(_ for _ in ()).throw(RuntimeError('missing executable'))
        with self.assertRaisesRegex(RuntimeError,'missing executable'):self.edit(ident,target,'new')
        self.assertEqual(doc,before);self.assertNotIn(ident,self.store.busy)
        self.assertEqual([r['id'] for r in self.store.listing([],False)],[ident])
        self.assertEqual(self.make_store().adopt(ident),before)

    def test_edit_preserves_context_before_later_user_item_and_busy_reservation(self):
        ident=self.start();self.send(ident,'deepseek','original')
        doc=self.store.adopt(ident);target=doc['turns'][0]
        target['items'].append({'id':'steer','type':'userMessage','content':[{'type':'text','text':'old steering'}]})
        target['items'].append({'id':'future','type':'agentMessage','text':'outdated answer'})
        original=self.store.call
        def checked(method,params):
            if method=='thread/start':
                with self.assertRaisesRegex(ValueError,'停止当前任务'):
                    self.store.handle('switch',{'threadId':ident,'engine':'codex'})
                self.assertIn('original',params['developerInstructions'])
                self.assertNotIn('old steering',params['developerInstructions'])
                self.assertNotIn('outdated answer',params['developerInstructions'])
            return original(method,params)
        self.store.call=checked
        result=self.edit(ident,target,'new steering',item='steer')
        self.assertEqual(len(result['thread']['turns']),2)
        self.assertEqual(result['thread']['turns'][0]['items'][-1]['id'],'answer')

    def test_usage_persists_across_switch_restart_and_adds_native_children_once(self):
        ident=self.start('codex');self.send(ident,'codex','one');doc=self.store.adopt(ident)
        first=doc['binding']['threadId']
        usage={first:{'tokenUsage':{'total':{'inputTokens':100,'outputTokens':20},'last':{'inputTokens':80},'modelContextWindow':1000}}}
        self.store.native_usage=lambda p:copy.deepcopy(usage[p['threadId']])
        self.assertEqual(self.store.usage({'threadId':ident})['tokenUsage']['total']['totalTokens'],120)
        switched=self.store.handle('switch',{'threadId':ident,'engine':'claude'})['thread']
        self.assertEqual(switched['usage']['tokenUsage']['total']['totalTokens'],120)
        self.assertTrue(switched['usage']['contextPending']);self.assertEqual(switched['usage']['contextEngine'],'codex')
        self.send(ident,'claude','two');second=doc['binding']['threadId']
        event={'type':'notification','method':'thread/tokenUsage/updated','params':{'threadId':second,
            'tokenUsage':{'total':{'inputTokens':200,'outputTokens':30,'totalTokens':230},'last':{'inputTokens':190}}}}
        self.store.translate(event,True);self.store.translate(event,True)
        value=self.store.usage_view(doc)
        self.assertEqual(value['tokenUsage']['total']['totalTokens'],350)
        self.assertEqual(value['byEngine']['claude']['inputTokens'],200)
        self.assertFalse(value['contextPending'])
        restored=self.make_store();self.assertEqual(restored.usage_view(restored.adopt(ident)),value)
        self.store.handle('switch',{'threadId':ident,'engine':'codex'})
        switched=self.store.usage_view(doc);self.assertTrue(switched['contextPending'])
        self.assertEqual(switched['tokenUsage']['total']['totalTokens'],350)
        self.send(ident,'codex','three');third=doc['binding']['threadId']
        usage[third]={'tokenUsage':{'total':{'inputTokens':50,'outputTokens':5},'last':{'inputTokens':40}}}
        self.assertEqual(self.store.usage({'threadId':ident})['tokenUsage']['total']['totalTokens'],405)
        self.assertEqual(self.store.usage({'threadId':ident})['tokenUsage']['total']['totalTokens'],405)
        self.assertGreaterEqual(doc['turns'][0]['durationMs'],0)

    def test_context_only_usage_is_not_counted_as_billed_and_offline_keeps_totals(self):
        ident=self.start();self.send(ident,'deepseek','one');doc=self.store.adopt(ident)
        self.store.native_usage=lambda p:{'tokenUsage':{'last':{'totalTokens':123},'total':{},'modelContextWindow':1000}}
        value=self.store.usage({'threadId':ident})
        self.assertEqual(value['tokenUsage']['total'],{});self.assertTrue(value['totalsPartial'])
        self.store.native_usage=lambda p:(_ for _ in ()).throw(OSError('offline'))
        self.assertEqual(self.store.usage({'threadId':ident}),value)

    def test_old_inactive_deepseek_child_is_recovered_after_switch(self):
        ident=self.start();self.send(ident,'deepseek','old model history');doc=self.store.adopt(ident)
        source=doc['binding']['threadId']
        self.store.record_usage(doc,'deepseek',source,{'tokenUsage':{'last':{'totalTokens':8},'total':{}}},model='test')
        self.store.handle('switch',{'threadId':ident,'engine':'codex'})
        calls=[]
        def native_usage(child):
            calls.append(child['threadId'])
            return {'nativeUsageRecovered':True,'tokenUsage':{'total':{'inputTokens':10,'outputTokens':3},
                'last':{'totalTokens':10},'modelContextWindow':1000,
                'byModel':[{'model':'test','total':{'inputTokens':10,'outputTokens':3}}]}}
        self.store.native_usage=native_usage
        first=self.store.usage({'threadId':ident})
        self.assertEqual(first['tokenUsage']['total']['totalTokens'],13)
        doc['binding']=None
        self.store.usage({'threadId':ident});self.assertEqual(calls.count(source),1)


if __name__=='__main__':unittest.main()
