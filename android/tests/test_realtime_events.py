"""Live independent/collaboration reducers must agree before terminal snapshots."""
import copy
import unittest
from realtime_events import apply, settle
from collaboration_transcript import AttemptTranscript
from conversation_usage import execution_stats


class RealtimeEventsTests(unittest.TestCase):
    def test_runtime_warnings_auth_mcp_and_model_verification_survive_collaboration(self):
        direct={'items':[]};transcript=AttemptTranscript()
        cases=[('warning',{'message':'retry soon'}),('modelProvider/authRecoveryStarted',{'message':'refreshing'}),
            ('modelProvider/authRecoveryCompleted',{'message':'ready'}),('model/verification',{'verifications':[{'model':'worker'}]}),
            ('model/safetyBuffering/updated',{'showBufferingUi':True,'reasons':['review']}),
            ('mcpServer/startupStatus/updated',{'name':'device','status':'failed','error':'unavailable'})]
        for method,p in cases:
            self.assertTrue(apply(direct,method,p));transcript.apply({'method':method,'params':p},flush=False)
        self.assertTrue(all(isinstance(i['text'],str) for i in direct['items']))
        self.assertEqual(direct['items'][-1]['status'],'failed')
        for i in transcript.value['items']:i.pop('startedAt',None)
        self.assertEqual(direct['items'],transcript.value['items'])
    def test_nullable_native_output_and_thinking_fields_accept_first_delta(self):
        turn={'items':[]}
        apply(turn,'item/started',{'item':{'id':'shell','type':'commandExecution','aggregatedOutput':None}})
        apply(turn,'item/commandExecution/outputDelta',{'itemId':'shell','delta':'first\n'})
        apply(turn,'item/started',{'item':{'id':'think','type':'reasoning','content':None,'summary':None}})
        apply(turn,'item/reasoning/textDelta',{'itemId':'think','delta':'Inspect'})
        self.assertEqual(turn['items'][0]['aggregatedOutput'],'first\n')
        self.assertEqual(turn['items'][1]['content'],['Inspect'])
        apply(turn,'item/completed',{'item':{'id':'shell','type':'commandExecution','aggregatedOutput':None}})
        self.assertEqual(turn['items'][0]['aggregatedOutput'],'first\n')

    def test_operations_and_out_of_order_output_survive_both_modes(self):
        direct={'items':[]};collab=AttemptTranscript()
        events=[
            ('item/commandExecution/outputDelta',{'itemId':'cmd','delta':'first\n'}),
            ('item/started',{'item':{'id':'cmd','type':'commandExecution','command':'echo first; sleep 2','status':'inProgress'}}),
            ('item/commandExecution/outputDelta',{'itemId':'cmd','delta':'second\n'}),
            ('item/reasoning/textDelta',{'itemId':'think','contentIndex':0,'delta':'Inspect '}),
            ('item/reasoning/textDelta',{'itemId':'think','contentIndex':0,'delta':'files'}),
            ('item/reasoning/textDelta',{'itemId':'think','contentIndex':1,'delta':'Then edit'}),
            ('item/plan/delta',{'itemId':'proposed','delta':'Read then edit'}),
            ('turn/plan/updated',{'plan':[{'step':'Read','status':'inProgress'},{'step':'Edit','status':'pending'}]}),
            ('item/fileChange/patchUpdated',{'itemId':'edit','changes':[{'path':'a.txt','diff':'+new'}]}),
            ('item/mcpToolCall/progress',{'itemId':'mcp','message':'Reading phone log'}),
            ('item/commandExecution/terminalInteraction',{'itemId':'cmd','stdin':'private-terminal-input'}),
            ('turn/diff/updated',{'diff':'+new'}),
            ('hook/started',{'run':{'id':'hook1','eventName':'preToolUse','entries':[]}}),
            ('hook/completed',{'run':{'id':'hook1','status':'completed','eventName':'preToolUse','entries':[]}}),
            ('error',{'error':{'message':'Connection interrupted'},'willRetry':True}),
            ('model/rerouted',{'fromModel':'a','toModel':'b'}),
        ]
        for method,params in events:
            self.assertTrue(apply(direct,method,params),method)
            collab.apply({'method':method,'params':copy.deepcopy(params)},flush=False)
            left=copy.deepcopy(collab.snapshot()['items'])
            for item in left:item.pop('startedAt',None)
            self.assertEqual(left,direct['items'],method)
        items={i['id']:i for i in direct['items']}
        self.assertEqual(items['cmd']['aggregatedOutput'],'first\nsecond\n')
        self.assertEqual(items['think']['content'],['Inspect files','Then edit'])
        self.assertNotIn('private-terminal-input',str(items))
        self.assertEqual(items['hook:hook1']['status'],'completed')
        collab.finish({'status':'interrupted'});settle(direct,'interrupted')
        self.assertFalse(any(i.get('status')=='inProgress' for i in collab.snapshot()['items']))

    def test_final_snapshot_replaces_delta_and_completion_without_status_is_terminal(self):
        turn={'items':[]}
        apply(turn,'item/agentMessage/delta',{'itemId':'a','delta':'partial'})
        apply(turn,'item/completed',{'item':{'id':'a','type':'agentMessage','text':'final'}})
        self.assertEqual(turn['items'],[{'id':'a','type':'agentMessage','status':'completed','text':'final'}])

    def test_interruption_does_not_report_unfinished_operations_as_successful(self):
        transcript=AttemptTranscript()
        transcript.apply({'method':'item/started','params':{'item':{'id':'c','type':'commandExecution','status':'inProgress'}}})
        transcript.finish({'status':'interrupted','turn':{'items':[{'id':'c','type':'commandExecution','status':'inProgress'}]}})
        self.assertEqual(transcript.snapshot()['items'][0]['status'],'interrupted')

    def test_aggregate_patch_display_does_not_count_a_second_tool_execution(self):
        turn={'items':[]}
        apply(turn,'item/started',{'item':{'id':'edit','type':'fileChange','status':'inProgress'}})
        apply(turn,'turn/diff/updated',{'diff':'+new'})
        self.assertEqual(execution_stats(turn['items'],'inProgress')['toolCalls'],1)
