"""One visible conversation must survive Agent changes, reloads and failures."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from shared_conversations import SharedConversations


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

    def test_import_rename_fork_archive_delete_preserve_native_history(self):
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
        self.assertIn('deepseek-old',self.native)
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


if __name__=='__main__':unittest.main()
