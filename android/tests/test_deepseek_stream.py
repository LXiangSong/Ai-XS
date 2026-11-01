"""Transient DSH frames must arrive early and reconcile with committed ACP output."""
import copy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import uuid

from deepseek_bridge import DeepSeekBridge
from conversation_usage import execution_stats


class DeepSeekStreamTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.events=[]
        self.bridge=DeepSeekBridge(SimpleNamespace(_emit=lambda e:self.events.append(copy.deepcopy(e)),approvals={}),self.tmp.name)
        tid='deepseek-'+str(uuid.uuid4())
        turn={'id':'turn','status':'inProgress','items':[]}
        self.run={'data':{'id':tid,'turns':[turn]},'turn':turn,'sessionId':'native'}
        self.bridge.runs[tid]=self.run;self.revision=0

    def tearDown(self):
        self.bridge.runs.clear();self.tmp.cleanup()

    def frame(self, type='chunk', session='native', attempt='a', **fields):
        self.revision+=1
        message={'method':'aixs/assistantStream','params':{'sessionId':session,
            'frame':{'type':type,'attemptId':attempt,'revision':self.revision,**fields}}}
        self.bridge._message(self.run,message);return message

    def update(self, **fields):
        self.bridge._message(self.run,{'method':'session/update','params':{'sessionId':'native','update':fields}})

    def test_thinking_and_text_arrive_before_commit_without_duplicate_final_messages(self):
        self.frame(type='start')
        self.frame(chunk={'type':'reasoning-delta','index':0,'text':'Inspect '})
        self.frame(chunk={'type':'reasoning-delta','index':0,'text':'files'})
        self.frame(chunk={'type':'text-delta','index':1,'text':'Live reply'})
        self.assertEqual([i['text'] for i in self.run['turn']['items']],['Inspect files','Live reply'])
        self.assertEqual([e['params']['delta'] for e in self.events if e['method']=='item/reasoning/textDelta'],['Inspect ','files'])
        self.assertTrue(all(i['status']=='inProgress' for i in self.run['turn']['items']))
        self.bridge._message(self.run,{'method':'aixs/assistantCommit','params':{'sessionId':'native','attemptId':'a','messageId':'m'}})
        self.frame(type='end',outcome={'kind':'committed','eventType':'assistant/message','seq':9})
        for kind,text in (('agent_thought_chunk','Inspect files'),('agent_message_chunk','Live reply')):
            self.update(sessionUpdate=kind,messageId='m',content={'type':'text','text':text})
        self.assertEqual(len(self.run['turn']['items']),2)
        self.assertTrue(all(i['status']=='completed' for i in self.run['turn']['items']))

    def test_tool_arguments_show_before_execution_and_result_reuses_same_item(self):
        self.frame(chunk={'type':'tool-call-delta','index':2,'id':'read-1','name':'read','argumentsDelta':'{"file_path":"/tmp/'})
        item=self.run['turn']['items'][0]
        self.assertIn('/tmp/',item['partialInput']);self.assertEqual(item['status'],'inProgress')
        self.assertEqual(item['type'],'toolCallDraft')
        self.frame(chunk={'type':'tool-call-delta','index':2,'id':'read-1','argumentsDelta':'check.txt"}'})
        self.frame(chunk={'type':'block-end','index':2,'block':{'type':'tool-call','id':'read-1','name':'read','arguments':'{"file_path":"/tmp/check.txt"}'}})
        self.update(sessionUpdate='tool_call',toolCallId='read-1',title='read',rawInput={'file_path':'/tmp/check.txt'},status='in_progress')
        self.update(sessionUpdate='tool_call_update',toolCallId='read-1',status='completed',content=[{'type':'text','text':'ok'}])
        self.assertEqual(len(self.run['turn']['items']),1)
        self.assertEqual(item['arguments'],{'file_path':'/tmp/check.txt'})
        self.assertEqual(item['partialInput'],'');self.assertEqual(item['status'],'completed')
        self.assertEqual(item['type'],'dynamicToolCall')

    def test_duplicate_frames_and_other_sessions_cannot_pollute_current_turn(self):
        self.frame(chunk={'type':'text-delta','index':0,'text':'other'},session='child')
        message=self.frame(chunk={'type':'text-delta','index':0,'text':'one'})
        self.bridge._message(self.run,message)
        self.assertEqual(len(self.run['turn']['items']),1)
        self.assertEqual(self.run['turn']['items'][0]['text'],'one')

    def test_retried_attempts_keep_distinct_ids_and_abandoned_prefix_is_not_completed(self):
        self.frame(chunk={'type':'text-delta','index':0,'text':'partial'})
        self.frame(type='end',outcome={'kind':'abandoned'})
        self.frame(chunk={'type':'text-delta','index':0,'text':'retry'},attempt='b')
        items=self.run['turn']['items']
        self.assertNotEqual(items[0]['id'],items[1]['id'])
        self.assertEqual(items[0]['status'],'failed');self.assertEqual(items[1]['status'],'inProgress')

    def test_standard_acp_fallback_remains_available_without_companion(self):
        self.update(sessionUpdate='agent_message_chunk',messageId='legacy',content={'type':'text','text':'Fallback'})
        self.assertEqual(self.run['turn']['items'][0]['text'],'Fallback')

    def test_child_stream_and_tool_lifecycle_use_own_ids_and_parent_lineage(self):
        def child(method,**params):
            self.bridge._message(self.run,{'method':method,'params':{'sessionId':'child','ancestors':['native'],**params}})
        child('aixs/childModel',model='worker-model')
        child('aixs/childLifecycle',type='turn/start',turn=1)
        child('aixs/assistantStream',frame={'type':'chunk','attemptId':'child-attempt','revision':1,'chunk':{'type':'text-delta','index':0,'text':'Child reply'}})
        child('aixs/toolOutput',itemId='same-native-id',delta='child output')
        child('aixs/childUpdate',update={'sessionUpdate':'tool_call_update','toolCallId':'same-native-id','status':'completed'})
        child('aixs/childLifecycle',type='turn/end',turn=1,reason='stop')
        items=self.run['turn']['items'];answer=next(i for i in items if i.get('text')=='Child reply')
        self.assertEqual(answer['type'],'subagentMessage');self.assertEqual(answer['sourceModel'],'worker-model')
        self.assertEqual(answer['status'],'completed')
        tool=next(i for i in items if i.get('aggregatedOutput')=='child output')
        self.assertTrue(tool['id'].startswith('dsh-child:child:'));self.assertEqual(tool['status'],'completed')

    def test_child_structured_failure_keeps_root_alive_and_reports_failed_child(self):
        def send(method,**p):self.bridge._message(self.run,{'method':method,'params':{'sessionId':'child','ancestors':['native'],**p}})
        send('aixs/childTask',id='activation',status='inProgress')
        self.assertTrue(self.run['activeChildTasks'])
        send('aixs/childLifecycle',type='turn/start',turn=1)
        send('aixs/runtimeError',message='child request failed')
        send('aixs/childLifecycle',type='turn/end',turn=1,reason={'kind':'error'})
        send('aixs/childTask',id='activation',status='failed')
        self.assertFalse(self.run['activeChildTasks'])
        self.assertFalse(any(e['method']=='turn/completed' for e in self.events))
        self.assertEqual(next(e for e in self.events if e['method']=='native/childTurn')['params']['stats']['statuses'],{'failed':1})
        self.assertEqual(self.run['turn'].get('status'),'inProgress')
        self.assertIn('child',self.run['data']['nativeSessions'])
        self.assertFalse(any(e['method']=='turn/completed' for e in self.events))

    def test_acp_plan_updates_are_forwarded_live(self):
        self.update(sessionUpdate='plan',entries=[{'content':'Read files','status':'completed'},{'content':'Edit','status':'in_progress'}])
        plan=next(e for e in self.events if e['method']=='turn/plan/updated')['params']['plan']
        self.assertEqual(plan,[{'step':'Read files','status':'completed'},{'step':'Edit','status':'inProgress'}])

    def test_live_command_output_and_elapsed_precede_final_result(self):
        def notify(method,**params):
            self.bridge._message(self.run,{'method':method,'params':{'sessionId':'native','itemId':'bash-1',**params}})
        notify('aixs/toolProgress',tool='bash',elapsedSeconds=0)
        notify('aixs/toolOutput',delta='first\n')
        notify('aixs/toolProgress',tool='bash',elapsedSeconds=1)
        item=self.run['turn']['items'][0]
        self.assertEqual(item['status'],'inProgress');self.assertEqual(item['aggregatedOutput'],'first\n')
        self.assertEqual(item['elapsedSeconds'],1)
        notify('aixs/toolOutput',delta='second\n')
        self.update(sessionUpdate='tool_call_update',toolCallId='bash-1',status='completed',content=[{'type':'text','text':'first\nsecond\n'}])
        self.assertEqual(len(self.run['turn']['items']),1)
        self.assertEqual(item['status'],'completed');self.assertEqual(item['aggregatedOutput'],'first\nsecond\n')
        self.assertEqual(len([e for e in self.events if e.get('method')=='item/commandExecution/outputDelta']),2)

    def test_abandoned_partial_tool_does_not_remain_running_during_retry(self):
        self.frame(chunk={'type':'tool-call-delta','index':0,'id':'partial','name':'bash','argumentsDelta':''})
        first=self.run['turn']['items'][0]
        self.assertEqual(first['progressMessage'],'正在生成工具参数')
        self.frame(type='end',outcome={'kind':'abandoned'})
        self.frame(chunk={'type':'text-delta','index':0,'text':'retry'},attempt='b')
        self.assertEqual(first['status'],'discarded')
        self.assertEqual(first['type'],'runtimeStatus')
        self.assertIn('未执行',first['tool'])
        self.assertEqual(self.run['turn']['items'][1]['status'],'inProgress')

    def test_final_native_id_reuses_stream_card_for_output_and_result(self):
        self.frame(chunk={'type':'tool-call-delta','index':0,'id':'prefix','name':'bash','argumentsDelta':'{"command":'})
        item=self.run['turn']['items'][0]
        self.frame(chunk={'type':'block-end','index':0,'block':{'type':'tool-call','id':'final-id','name':'bash','arguments':'{"command":"echo ok"}'}})
        self.bridge._message(self.run,{'method':'aixs/toolOutput','params':{'sessionId':'native','itemId':'final-id','delta':'ok\n'}})
        self.update(sessionUpdate='tool_call',toolCallId='final-id',title='bash',rawInput={'command':'echo ok'},status='in_progress')
        self.update(sessionUpdate='tool_call_update',toolCallId='final-id',status='completed')
        self.assertEqual(len(self.run['turn']['items']),1)
        self.assertEqual(item['aggregatedOutput'],'ok\n')
        self.assertEqual(item['status'],'completed')
        output=next(e for e in self.events if e['method']=='item/commandExecution/outputDelta')
        self.assertEqual(output['params']['itemId'],item['id'])

    def test_retry_reusing_native_id_does_not_complete_abandoned_draft(self):
        self.frame(chunk={'type':'tool-call-delta','index':0,'id':'reused','name':'bash','argumentsDelta':'{"description"'})
        self.frame(type='end',outcome={'kind':'committed','eventType':'assistant/attempt','seq':1})
        self.frame(chunk={'type':'tool-call-delta','index':0,'id':'reused','name':'bash','argumentsDelta':'{"command":"echo ok"}'},attempt='b')
        self.frame(type='end',attempt='b',outcome={'kind':'committed','eventType':'assistant/message','seq':2})
        self.update(sessionUpdate='tool_call',toolCallId='reused',title='bash',status='in_progress')
        self.update(sessionUpdate='tool_call_update',toolCallId='reused',status='completed')
        first,second=self.run['turn']['items']
        self.assertNotEqual(first['id'],second['id'])
        self.assertEqual(first['status'],'discarded')
        self.assertEqual(second['status'],'completed')
        self.assertEqual([i['type'] for i in self.run['turn']['items']],['runtimeStatus','dynamicToolCall'])
        self.assertEqual(execution_stats(self.run['turn']['items'],'completed')['toolCalls'],1)

    def test_two_real_identical_commands_are_not_deduplicated(self):
        for attempt in ('a','b'):
            self.frame(chunk={'type':'tool-call-delta','index':0,'id':attempt,'name':'bash','argumentsDelta':'{"command":"echo ok"}'},attempt=attempt)
            self.frame(type='end',attempt=attempt,outcome={'kind':'committed','eventType':'assistant/message','seq':2})
            self.update(sessionUpdate='tool_call',toolCallId=attempt,title='bash',rawInput={'command':'echo ok'},status='in_progress')
            self.update(sessionUpdate='tool_call_update',toolCallId=attempt,status='completed')
        self.assertEqual(len(self.run['turn']['items']),2)
        self.assertTrue(all(i['type']=='dynamicToolCall' and i['status']=='completed' for i in self.run['turn']['items']))
