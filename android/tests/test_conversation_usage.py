"""Accounting invariants across model changes, concurrent sources and restarts."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from types import SimpleNamespace

sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from conversation_usage import record, breakdown, execution_stats
from claude_bridge import ClaudeBridge
from deepseek_bridge import DeepSeekBridge
from shared_conversations import SharedConversations
from collaboration_session import ConversationSessions
from collaboration_transcript import AttemptTranscript


def chat():
    return {'engine':'codex','model':'requested/model','turns':[],
        'collaborationParticipants':[],'children':[],'binding':None}


def report(total=None, used=None, window=None):
    tokens={}
    if total is not None:tokens['total']={'inputTokens':total,'outputTokens':2}
    if used is not None:tokens['last']={'totalTokens':used}
    if window is not None:tokens['modelContextWindow']=window
    return {'tokenUsage':tokens}


def attempt(doc, source, engine, model, usage, status='succeeded', tools=1):
    stats=execution_stats([{'type':'userMessage'},{'type':'agentMessage'}]+[{'type':'mcpToolCall'}]*tools,status,10,12)
    return record(doc,engine,source,usage,model,{'mode':'collaboration','status':status,
        'taskId':source,'attemptId':source,'startedAt':10,'finishedAt':12,'stats':stats})


def by_model(value):return {(g['engine'],g['model']):g for g in value['byModel']}


class ConversationUsageTests(unittest.TestCase):
    def test_all_engines_arbitrary_models_and_retired_config_survive_restart(self):
        doc=chat()
        for i,engine in enumerate(('codex','claude','deepseek')):
            attempt(doc,'old-'+engine,engine,'gateway/old-'+engine,report(10+i,30,100))
            attempt(doc,'new-'+engine,engine,'gateway/new-'+engine,report(20+i,40,200))
            doc['collaborationParticipants'].append({'engine':engine,'model':'gateway/new-'+engine})
        doc['turns']=[{'workflowId':'one','items':[{'type':'userMessage'}]}]
        result=breakdown(json.loads(json.dumps(doc)));groups=by_model(result)
        self.assertEqual(result['total']['totalTokens'],108)
        self.assertEqual(result['sessionStats']['executions'],6)
        self.assertEqual(result['sessionStats']['turns'],1)
        self.assertEqual(result['sessionStats']['userMessages'],1)
        self.assertEqual(result['sessionStats']['toolCalls'],6)
        for engine in ('codex','claude','deepseek'):
            old=groups[engine,'gateway/old-'+engine];new=groups[engine,'gateway/new-'+engine]
            self.assertTrue(old['historical']);self.assertFalse(old['isConfigured'])
            self.assertTrue(new['isConfigured']);self.assertEqual(old['stats']['executions'],1)
            self.assertEqual(old['context']['usedTokens'],30)
        self.assertEqual(breakdown(copy.deepcopy(doc)),result)

    def test_parallel_same_model_has_separate_windows_and_duplicate_snapshots_do_not_bill(self):
        doc=chat()
        attempt(doc,'one','codex','shared',report(100,80,1000),'running')
        attempt(doc,'two','codex','shared',report(200,150,2000),'running')
        before=breakdown(doc)
        self.assertFalse(attempt(doc,'one','codex','shared',report(100,80,1000),'running'))
        self.assertEqual(before,breakdown(doc))
        group=by_model(before)['codex','shared']
        self.assertEqual(group['tokenUsage']['total']['totalTokens'],304)
        self.assertEqual(sorted(c['usedTokens'] for c in group['contexts']),[80,150])
        self.assertEqual(group['stats']['active'],2)
        self.assertNotIn('modelContextWindow',before)
        attempt(doc,'one','codex','shared',report(120,95,1000),'interrupted')
        after=by_model(breakdown(doc))['codex','shared']
        self.assertEqual(after['tokenUsage']['total']['totalTokens'],324)
        self.assertEqual(after['stats']['executions'],2)
        self.assertEqual(after['stats']['statuses'],{'interrupted':1,'running':1})

    def test_reused_native_thread_tracks_each_models_delta_and_return(self):
        doc=chat()
        for model,total in [('old',10),('old',10),('new',30),('old',45)]:
            record(doc,'claude','native',report(total,7,100),model)
        groups=by_model(breakdown(doc))
        self.assertEqual(groups['claude','old']['tokenUsage']['total']['totalTokens'],27)
        self.assertEqual(groups['claude','new']['tokenUsage']['total']['totalTokens'],20)
        self.assertEqual(breakdown(doc)['total']['totalTokens'],47)

    def test_unknown_usage_failed_start_and_context_only_are_not_zero_bills(self):
        doc=chat()
        attempt(doc,'fail','claude','missing',{},'failed')
        attempt(doc,'acp','deepseek','external/model',report(used=123,window=1000))
        result=breakdown(doc);groups=by_model(result)
        self.assertEqual(result['total'],{});self.assertTrue(result['totalsPartial'])
        self.assertEqual(groups['deepseek','external/model']['context']['ratio'],.123)
        self.assertFalse(groups['claude','missing']['context']['reported'])
        self.assertIsNone(groups['claude','missing']['context']['usedTokens'])
        self.assertFalse(groups['deepseek','external/model']['totalsReported'])
        record(doc,'deepseek','acp',report(5,123,1000),'external/model')
        record(doc,'deepseek','acp',report(used=125,window=1000),'external/model')
        self.assertEqual(by_model(breakdown(doc))['deepseek','external/model']['tokenUsage']['total']['totalTokens'],7)

    def test_legacy_unknown_is_not_relabelled_and_counter_regressions_are_partial(self):
        doc=chat();doc['usageSnapshots']={'legacy':{'engine':'codex','tokenUsage':{'total':{'totalTokens':50}}}}
        record(doc,'codex','legacy',{'tokenUsage':{'total':{'totalTokens':60}}},'new')
        groups=by_model(breakdown(doc))
        self.assertEqual(groups['codex',None]['tokenUsage']['total']['totalTokens'],50)
        self.assertEqual(groups['codex','new']['tokenUsage']['total']['totalTokens'],10)
        record(doc,'codex','legacy',{'tokenUsage':{'total':{'totalTokens':5}}},'new')
        result=breakdown(doc)
        self.assertEqual(result['total']['totalTokens'],60);self.assertTrue(result['totalsPartial'])

    def test_native_full_model_breakdown_replaces_provisional_attribution_once(self):
        doc=chat();record(doc,'claude','native',report(10),'requested')
        usage={'tokenUsage':{'total':{'totalTokens':20},'byModel':[
            {'model':'actual/a','total':{'totalTokens':12},'last':{'totalTokens':8},'modelContextWindow':100},
            {'model':'actual/b','total':{'totalTokens':8}}]},'model':'actual/a'}
        record(doc,'claude','native',usage,'requested',{'status':'running'})
        self.assertFalse(record(doc,'claude','native',usage,'requested',{'status':'running'}))
        result=breakdown(doc);groups=by_model(result)
        self.assertEqual(result['total']['totalTokens'],20)
        self.assertNotIn(('claude','requested'),groups)
        self.assertTrue(groups['claude','actual/a']['isActive'])
        self.assertFalse(groups['claude','actual/b']['isActive'])
        self.assertTrue(groups['claude','actual/b']['stats']['partial'])

    def test_incomplete_and_invalid_reports_keep_unknown_values(self):
        doc=chat();record(doc,'codex','native',{'tokenUsage':{'total':{'inputTokens':12},
            'last':{'totalTokens':True},'modelContextWindow':float('inf')}},'any')
        group=by_model(breakdown(doc))['codex','any']
        self.assertEqual(group['tokenUsage']['total'],{'inputTokens':12})
        self.assertTrue(group['totalsPartial']);self.assertIsNone(group['context']['usedTokens'])
        self.assertIsNone(group['context']['windowTokens'])

    def test_first_aggregate_read_of_old_mixed_model_thread_is_unattributed(self):
        doc=chat();doc['turns']=[{'id':'codex:native:'+str(i),'engine':'codex','model':model,'items':[]}
            for i,model in enumerate(('before','after'))]
        record(doc,'codex','native',report(10),'after')
        groups=by_model(breakdown(doc))
        self.assertEqual(groups['codex',None]['tokenUsage']['total']['totalTokens'],12)
        self.assertEqual(groups['codex','after']['tokenUsage']['total'],{})

    def test_native_history_context_time_does_not_move_when_only_another_model_runs(self):
        doc=chat();old={'model':'old','total':{'totalTokens':10},'last':{'totalTokens':8}}
        record(doc,'claude','native',{'tokenUsage':{'byModel':[old]},'recordedAt':'first','updatedAt':100},'old')
        record(doc,'claude','native',{'tokenUsage':{'byModel':[old,{'model':'new','total':{'totalTokens':20}}]},
            'recordedAt':'second','updatedAt':200},'new')
        context=by_model(breakdown(doc))['claude','old']['context']
        self.assertEqual(context['recordedAt'],'first');self.assertEqual(context['updatedAt'],100)

    def test_recovered_native_turn_clears_persisted_active_context(self):
        doc=chat();doc['turns']=[{'id':'codex:native:turn','engine':'codex','model':'old','status':'interrupted','items':[]}]
        record(doc,'codex','native',report(10,8,100),'old',{'mode':'independent','status':'inProgress'})
        group=by_model(breakdown(doc))['codex','old']
        self.assertFalse(group['isActive']);self.assertFalse(group['context']['active'])
        self.assertEqual(group['stats']['statuses'],{'interrupted':1})
        self.assertTrue(group['stats']['partial'])


class AdapterUsageTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.events=[]
        app=type('App',(),{'_emit':lambda _,event:self.events.append(event),'approvals':{}})()
        self.bridge=ClaudeBridge(app,Path(self.tmp.name)/'claude')

    def tearDown(self):self.tmp.cleanup()

    def run_data(self,model='thirdparty/model'):
        return {'data':{'id':'claude-'+str(uuid.uuid4()),'model':model,'turns':[]},'turn':{'id':'t','items':[]},'stopping':False}

    def message(self,run,model,mid,usage):
        run['lastUsage']=usage
        self.bridge._usage_message(run,{'id':mid,'model':model,'usage':usage})

    def test_claude_final_multimodel_and_stream_deduplicate_then_switch(self):
        run=self.run_data()
        self.message(run,'thirdparty/model','m1',{'input_tokens':10,'output_tokens':2,'cache_read_input_tokens':5})
        self.message(run,'thirdparty/model','m1',{'input_tokens':10,'output_tokens':4,'cache_read_input_tokens':5})
        self.assertEqual(run['data']['tokenUsage']['total']['totalTokens'],19)
        self.bridge._usage_update(run,{'usage':{'input_tokens':100,'output_tokens':30,'cache_read_input_tokens':50},'modelUsage':{
            'thirdparty/model':{'inputTokens':90,'outputTokens':20,'cacheReadInputTokens':50,'contextWindow':1000},
            'another/model':{'inputTokens':10,'outputTokens':10,'contextWindow':2000}}})
        usage=run['data']['tokenUsage'];models={m['model']:m for m in usage['byModel']}
        self.assertEqual(usage['total']['totalTokens'],180)
        self.assertEqual(models['thirdparty/model']['total']['totalTokens'],160)
        self.assertEqual(models['another/model']['total']['totalTokens'],20)
        self.assertEqual(models['thirdparty/model']['last']['totalTokens'],19)
        self.assertNotIn('last',models['another/model'])
        run2=self.run_data('new/model');run2['data']['tokenUsage']=copy.deepcopy(usage)
        self.message(run2,'new/model','m2',{'input_tokens':5,'output_tokens':1})
        self.bridge._usage_update(run2,{'usage':{'input_tokens':5,'output_tokens':1}})
        self.assertEqual(run2['data']['tokenUsage']['total']['totalTokens'],186)
        self.assertEqual({m['model'] for m in run2['data']['tokenUsage']['byModel']},{'thirdparty/model','another/model','new/model'})

    def test_claude_missing_usage_does_not_synthesize_a_zero_bill(self):
        run=self.run_data();self.bridge._usage_update(run,{})
        self.assertEqual(run['data']['tokenUsage']['total'],{})
        self.assertEqual(self.bridge._claude_breakdown({}),{})
        self.assertNotIn('reasoningOutputTokens',self.bridge._claude_breakdown({'input_tokens':1,'output_tokens':2}))

    def test_deepseek_context_omission_is_unknown_and_zero_is_a_real_report(self):
        bridge=DeepSeekBridge(self.bridge.app,Path(self.tmp.name)/'deepseek');run=self.run_data()
        run['data']['id']='deepseek-'+str(uuid.uuid4());bridge.runs[run['data']['id']]=run
        for update in ({'size':1000},{'used':0,'size':1000},{'used':123,'size':1000}):
            bridge._message(run,{'method':'session/update','params':{'update':{'sessionUpdate':'usage_update',**update}}})
            last=self.events[-1]['params']['tokenUsage']['last']
            self.assertEqual(last,{'totalTokens':update['used']} if 'used' in update else {})
            self.assertEqual(self.events[-1]['params']['model'],'thirdparty/model')


class SessionAccountingTests(unittest.TestCase):
    def test_full_transcript_planner_and_attempt_config_survive_reassignment(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp);chats=SharedConversations(folder/'chats',None,None,None,lambda *a:'ask')
            doc=chats._new({'cwd':temp});chats._save(doc)
            transcript=AttemptTranscript(folder/'attempts'/'old'/'transcript.json')
            for i in range(125):
                transcript.apply({'method':'item/completed','params':{'item':{'id':str(i),'type':'commandExecution'}}},flush=False)
            transcript.flush(force=True)
            config={'engine':'claude','model':'old-model','name':'old person'}
            (folder/'attempts'/'old'/'spec.json').write_text(json.dumps({'config':config}))
            workflow={'conversationId':doc['id'],'participants':[{'id':'new','engine':'deepseek','model':'new-model'}],
                'tasks':[{'id':'task','participantId':'new','attempts':[
                    {'id':'old','status':'succeeded','startedAt':1,'finishedAt':2,'usage':report(10)},
                    {'id':'legacy','status':'failed','startedAt':2}]}],
                'planningHistory':[{'id':'planner','participantId':'new','attempts':[
                    {'id':'plan','status':'succeeded','startedAt':0,'finishedAt':1,'config':{'engine':'codex','model':'planner-model'},'usage':report(5)}]}]}
            service=SimpleNamespace(chats=chats,folder=folder,store=SimpleNamespace(read=lambda key:workflow))
            sessions=ConversationSessions(service);sessions.sync('workflow')
            usage=breakdown(doc);groups=by_model(usage)
            self.assertEqual(groups['claude','old-model']['stats']['toolCalls'],125)
            self.assertEqual(groups['codex','planner-model']['stats']['executions'],1)
            self.assertEqual(groups['unknown',None]['stats']['statuses'],{'failed':1})
            self.assertNotIn(('deepseek','new-model'),groups)
            self.assertEqual(usage['sessionStats']['executions'],3)
            sessions.sync('workflow');self.assertEqual(breakdown(doc),usage)


if __name__=='__main__':unittest.main()
