"""Native descendants must stream into one owner without corrupting its lifecycle."""
import copy
import unittest
from native_children import CodexChildren
from realtime_events import apply
from collaboration_transcript import AttemptTranscript


class NativeChildrenTests(unittest.TestCase):
    def setUp(self):
        self.watched=[];self.router=CodexChildren(self.watched.append)
        self.event('turn/started','root',turn={'id':'root-turn','status':'inProgress','items':[]})

    def event(self, method, tid='child', **params):
        return self.router.observe({'type':'notification','method':method,'params':dict(threadId=tid,**params)})

    def child(self):
        self.event('item/started','root',turnId='root-turn',item={'id':'spawn','type':'collabAgentToolCall','tool':'spawnAgent','receiverThreadIds':['child'],'model':'worker-model'})
        self.event('turn/started',turn={'id':'child-turn','status':'inProgress','items':[]})

    def test_child_deltas_hooks_tools_and_final_preserve_root_turn_and_distinct_ids(self):
        self.child();self.assertEqual(self.watched,['child'])
        direct={'items':[]};collab=AttemptTranscript()
        for method,params in [
            ('item/agentMessage/delta',{'itemId':'same','delta':'Child reply'}),
            ('item/commandExecution/outputDelta',{'itemId':'shell','delta':'first\n'}),
            ('hook/started',{'run':{'id':'hook','eventName':'PreToolUse'}}),
            ('hook/completed',{'run':{'id':'hook','eventName':'PreToolUse','status':'completed'}}),
        ]:
            for event in self.event(method,turnId='child-turn',**params):
                self.assertEqual(event['params']['threadId'],'root');self.assertEqual(event['params']['turnId'],'root-turn')
                apply(direct,event['method'],event['params']);collab.apply(event,flush=False)
        answer=next(i for i in direct['items'] if i.get('text')=='Child reply')
        self.assertEqual(answer['type'],'subagentMessage');self.assertEqual(answer['sourceModel'],'worker-model')
        final=self.event('turn/completed',turn={'id':'child-turn','status':'completed','items':[],'startedAt':10,'completedAt':12,'durationMs':1234})
        self.assertFalse(any(e['method']=='turn/completed' for e in final))
        for event in final:apply(direct,event['method'],event['params'])
        self.assertTrue(all(i['status']=='completed' for i in direct['items']))
        self.assertEqual(next(e for e in final if e['method']=='native/childTurn')['params']['stats']['durationMs'],1234)

    def test_explicit_parent_chain_and_late_children_do_not_move_to_new_root_turn(self):
        self.child();self.router.bind({'id':'nested','parentThreadId':'child'})
        self.event('turn/started','nested',turn={'id':'nested-turn','status':'inProgress','items':[]})
        self.event('turn/started','root',turn={'id':'new-turn','status':'inProgress','items':[]})
        event=self.event('item/agentMessage/delta','nested',turnId='nested-turn',itemId='reply',delta='Nested')[0]
        self.assertEqual(event['params']['threadId'],'root');self.assertEqual(event['params']['turnId'],'root-turn')
        unrelated=self.event('item/agentMessage/delta','independent',turnId='other',itemId='reply',delta='Unrelated')[0]
        self.assertEqual(unrelated['params']['threadId'],'independent')

    def test_child_usage_aggregates_model_totals_once_with_parent_context_preserved(self):
        self.child();self.router.models['root']='leader-model'
        parent={'total':{'inputTokens':10,'outputTokens':2,'totalTokens':12},'last':{'totalTokens':12},'modelContextWindow':100}
        self.event('thread/tokenUsage/updated','root',tokenUsage=parent)
        child={'total':{'inputTokens':4,'outputTokens':3,'totalTokens':7},'last':{'totalTokens':7},'modelContextWindow':50}
        for _ in range(2):event=self.event('thread/tokenUsage/updated',tokenUsage=child)[0]
        usage=event['params']['tokenUsage'];self.assertEqual(usage['total']['totalTokens'],19)
        self.assertEqual(usage['last']['totalTokens'],12);self.assertEqual(usage['modelContextWindow'],100)
        self.assertEqual({v['model']:v['total']['totalTokens'] for v in usage['byModel']},{'leader-model':12,'worker-model':7})
        self.assertEqual(self.router.merge_usage('root',parent)['total']['totalTokens'],19)

    def test_approval_keeps_original_request_id_and_child_identity(self):
        self.child()
        events=self.router.observe({'type':'approval','id':'42','method':'item/commandExecution/requestApproval',
            'params':{'threadId':'child','turnId':'child-turn','itemId':'shell','command':'pwd'}})
        self.assertEqual(events[0]['id'],'42');self.assertEqual(events[0]['params']['threadId'],'root')
        self.assertEqual(events[0]['params']['sourceThreadId'],'child')

    def test_fast_completed_child_snapshot_is_replayed_once_without_ending_parent(self):
        self.child()
        snapshot={'id':'child','turns':[{'id':'fast','status':'completed','items':[{'id':'r','type':'agentMessage','text':'Fast child'}]}]}
        events=self.router.replay(snapshot,'worker-model')
        self.assertTrue(any(e['method']=='native/childTurn' for e in events))
        self.assertFalse(any(e['method']=='turn/completed' for e in events))
        self.assertEqual(self.router.replay(snapshot,'worker-model'),[])

    def test_child_statistics_are_attributed_once_in_both_transcripts(self):
        from conversation_usage import execution_models
        self.child();events=[]
        events+=self.event('item/started',turnId='child-turn',item={'id':'tool','type':'dynamicToolCall','status':'inProgress'})
        events+=self.event('turn/completed',turn={'id':'child-turn','status':'completed','items':[],'startedAt':10,'completedAt':12})
        transcript=AttemptTranscript()
        for event in events:transcript.apply(event,flush=False)
        snap=transcript.snapshot()
        groups=execution_models(snap['items'],'codex','leader-model','completed',9,13,snap.get('nativeChildren'))
        self.assertEqual({g['model']:g['stats']['toolCalls'] for g in groups},{'leader-model':0,'worker-model':1})

    def test_restart_retains_child_usage_and_delete_removes_it(self):
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            self.router=CodexChildren(folder=folder);self.child()
            self.event('thread/tokenUsage/updated',tokenUsage={'total':{'totalTokens':7}})
            router=CodexChildren(folder=folder)
            self.assertEqual(router.merge_usage('root',{'total':{'totalTokens':12}})['total']['totalTokens'],19)
            router.forget('root')
            self.assertEqual(CodexChildren(folder=folder).merge_usage('root',{'total':{'totalTokens':12}})['total']['totalTokens'],12)

    def test_parent_terminal_waits_for_children_and_interrupt_still_finishes_once(self):
        self.child()
        events=self.event('turn/completed','root',turn={'id':'root-turn','status':'completed','items':[]})
        self.assertFalse(any(e['method']=='turn/completed' for e in events))
        self.assertEqual(self.router.active_children('root','root-turn'),[('child','child-turn')])
        self.router.interrupt('root','root-turn')
        events=self.event('turn/completed',turn={'id':'child-turn','status':'interrupted','items':[]})
        terminal=[e for e in events if e['method']=='turn/completed']
        self.assertEqual(len(terminal),1);self.assertEqual(terminal[0]['params']['turn']['status'],'interrupted')

    def test_wait_cannot_claim_unrelated_threads_or_replace_the_spawn_tool(self):
        self.child()
        self.event('item/started','root',turnId='root-turn',item={'id':'wait','type':'collabAgentToolCall','tool':'wait','receiverThreadIds':['child','unrelated']})
        self.assertNotIn('unrelated',self.router.parents)
        self.assertEqual(self.router.parent_tools['child'],'spawn')

    def test_thread_warning_before_child_start_cannot_keep_parent_running_forever(self):
        from conversation_usage import execution_models
        self.event('item/started','root',turnId='root-turn',item={'id':'spawn','type':'collabAgentToolCall','tool':'spawnAgent','receiverThreadIds':['child']})
        transcript=AttemptTranscript()
        for event in self.event('warning',message='Model metadata unavailable'):
            transcript.apply(event,flush=False)
        self.assertEqual(self.router.active_children('root','root-turn'),[])
        self.event('turn/started',turn={'id':'child-turn','status':'inProgress','items':[]})
        self.event('turn/completed','root',turn={'id':'root-turn','status':'completed','items':[]})
        events=self.event('turn/completed',turn={'id':'child-turn','status':'completed','items':[]})
        self.assertEqual(len([e for e in events if e['method']=='turn/completed']),1)
        self.assertEqual(self.router.active_children('root','root-turn'),[])
        for event in events:transcript.apply(event,flush=False)
        snapshot=transcript.snapshot()
        groups=execution_models(snapshot['items'],'codex','leader','completed',1,3,snapshot.get('nativeChildren'))
        self.assertEqual(sum(g['stats']['executions'] for g in groups),2,groups)

    def test_root_model_changes_keep_historical_bills_when_child_usage_is_merged(self):
        self.child()
        for model,total in [('old',10),('new',20),('old',25)]:
            self.router.set_model('root',model)
            self.event('thread/tokenUsage/updated','root',tokenUsage={'total':{'totalTokens':total},'last':{'totalTokens':5}})
        result=self.event('thread/tokenUsage/updated',tokenUsage={'total':{'totalTokens':7}})[0]['params']['tokenUsage']
        self.assertEqual({r['model']:r['total']['totalTokens'] for r in result['byModel']},{'old':15,'new':10,'worker-model':7})
        self.assertEqual(result['total']['totalTokens'],32)

    def test_child_interrupt_rejection_cannot_prevent_parent_rpc(self):
        import os
        from pathlib import Path
        import subprocess
        import sys
        import tempfile
        script='''
from agent_core import AppServer
app=AppServer();app.codex_children.watch=None
def event(method,tid,**params):
    app._codex_event({'type':'notification','method':method,'params':dict(threadId=tid,**params)})
event('turn/started','root',turn={'id':'r','status':'inProgress','items':[]})
event('item/started','root',turnId='r',item={'id':'spawn','type':'collabAgentToolCall','tool':'spawnAgent','receiverThreadIds':['child']})
event('turn/started','child',turn={'id':'c','status':'inProgress','items':[]})
sent=[]
def write(request):
    sent.append(request['params']['threadId'])
    reply={'error':{'message':'turn already finished'}} if sent[-1]=='child' else {'result':None}
    app.pending[request['id']].put(reply)
app._write=write
assert app.call('turn/interrupt',{'threadId':'root','turnId':'r'}) is None
assert sent==['child','root'],sent
assert any('继续停止主任务' in e.get('params',{}).get('item',{}).get('text','') for e in app.events),app.events
assert not app.pending,app.pending
'''
        with tempfile.TemporaryDirectory() as folder:
            env=dict(os.environ,AI_XS_STATE_DIR=folder)
            result=subprocess.run([sys.executable,'-c',script],cwd=Path(__file__).resolve().parents[2],env=env,capture_output=True,text=True,timeout=15)
            self.assertEqual(result.returncode,0,result.stderr)
