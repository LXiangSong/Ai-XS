"""Interrupt/continue preserves the old task and keeps follow-ups behind it."""
import json
import time
import unittest
from unittest.mock import patch
import test_conversation_queue as queue_tests


class ExecutionHostTests(unittest.TestCase):
    setUp=queue_tests.QueueHostTests.setUp
    tearDown=queue_tests.QueueHostTests.tearDown
    setUpClass=queue_tests.QueueHostTests.setUpClass
    start_host=queue_tests.QueueHostTests.start_host
    stop_host=queue_tests.QueueHostTests.stop_host
    call=queue_tests.QueueHostTests.call
    result=queue_tests.QueueHostTests.result
    configured=queue_tests.QueueHostTests.configured
    wait_workflow=queue_tests.QueueHostTests.wait_workflow
    inline_chat=queue_tests.QueueHostTests.inline_chat
    until=queue_tests.QueueHostTests.until
    thread=queue_tests.QueueHostTests.thread
    entries=queue_tests.QueueHostTests.entries
    queued=queue_tests.QueueHostTests.queued
    start=queue_tests.QueueHostTests.start

    def control(self,key,action):return self.result('native/chat/execution/'+action,{'threadId':key})
    def interrupted(self,key):
        return self.until(lambda:self.control(key,'read')['thread'],lambda d:d['executionControl']['status']=='interrupted')

    def test_all_agents_interrupt_auto_runs_queued_followup_without_continue(self):
        for engine in ('codex','claude','deepseek'):
            with self.subTest(engine=engine):
                key,turn=self.start(engine);follow=self.queued(key,'after interrupt','next-'+engine)
                self.control(key,'interrupt')
                entries=self.until(lambda:self.entries(key),lambda es:es[0]['status']=='sent')
                self.assertFalse(entries[0].get('executionResume'))
                doc=self.until(lambda:self.thread(key),lambda d:len(d['turns'])==2 and d['turns'][-1]['status']=='completed')
                self.assertEqual(doc['executionControl']['status'],'superseded')
                self.assertEqual(doc['turns'][0]['status'],'interrupted')
                self.assertEqual(doc['turns'][1]['items'][0]['content'][0]['text'],'after interrupt')
                self.assertEqual(len({t['id'].split(':')[1] for t in doc['turns']}),1)
                self.assertFalse(any(e.get('executionResume') for e in self.entries(key)))

    def test_continue_does_not_resume_an_explicitly_paused_followup_queue(self):
        key,turn=self.start();self.queued(key,'later one','one');self.queued(key,'later two','two')
        self.result('native/chat/queue/pause',{'threadId':key});self.control(key,'interrupt');self.interrupted(key)
        self.control(key,'resume')
        doc=self.until(lambda:self.thread(key),lambda d:len(d['turns'])==2 and d['turns'][-1]['status']=='completed')
        self.assertTrue(doc['messageQueuePaused']);self.assertEqual([e['status'] for e in self.entries(key)],['sent','queued','queued'])
        self.result('native/chat/queue/resume',{'threadId':key})
        self.until(lambda:self.thread(key),lambda d:len(d['turns'])==4 and d['turns'][-1]['status']=='completed')

    def test_interrupted_execution_survives_service_restart_without_auto_continue(self):
        key,turn=self.start();self.control(key,'interrupt');self.interrupted(key)
        self.stop_host();self.start_host();self.configured();time.sleep(.4)
        self.assertEqual(self.thread(key)['executionControl']['status'],'interrupted');self.assertEqual(len(self.thread(key)['turns']),1)
        self.control(key,'resume')
        self.until(lambda:self.thread(key),lambda d:len(d['turns'])==2 and d['turns'][-1]['status']=='completed')

    def test_collaboration_continues_same_workflow_and_keeps_finished_members(self):
        self.configured();key=self.inline_chat(('codex','codex'))
        first=self.result('native/collaboration/session/submit',{'threadId':key,'clientRequestId':'pause-team','input':[{'type':'text','text':'wait-for-interrupt'}]})['workflow']
        doc=self.wait_workflow(first['id'],lambda d:all(t['status']=='running' and t['attempts'] and t['attempts'][-1]['events'] for t in d['tasks']))
        selected=doc['tasks'][0]['id'];entry=self.queued(key,'finish-after-guide: completed member','finish-one','collaboration')
        self.result('native/chat/queue/include',{'threadId':key,'messageId':entry['id'],'taskIds':[selected]})
        self.wait_workflow(first['id'],lambda d:d['tasks'][0]['status']=='succeeded')
        workspace=self.folder/'state/collaboration/attempts'/doc['tasks'][1]['attempts'][-1]['id']/'workspace'
        (workspace/'saved-progress.txt').write_text('preserve this before continuing')
        follow=self.queued(key,'next team round','next-team','collaboration')
        self.result('native/chat/queue/pause',{'threadId':key})
        self.control(key,'interrupt');self.interrupted(key)
        doc=self.result('native/collaboration/read',{'workflowId':first['id']})['workflow']
        self.assertEqual([t['status'] for t in doc['tasks']],['succeeded','interrupted'])
        time.sleep(.4);self.assertEqual(next(e for e in self.entries(key) if e['id']==follow['id'])['status'],'queued')
        self.result('native/collaboration/resume',{'workflowId':first['id']});self.control(key,'resume')
        doc=self.wait_workflow(first['id'],lambda d:d['tasks'][1]['status']=='running' and len(d['tasks'][1]['attempts'])==2)
        resumed=self.folder/'state/collaboration/attempts'/doc['tasks'][1]['attempts'][-1]['id']/'workspace'
        self.assertEqual((resumed/'saved-progress.txt').read_text(),'preserve this before continuing')
        self.assertEqual(doc['tasks'][0]['status'],'succeeded');self.assertEqual(len(doc['tasks'][0]['attempts']),1)
        self.assertEqual(len(self.thread(key)['turns']),1)
        self.assertEqual(self.thread(key)['executionControl']['workflowId'],first['id'])
        self.result('native/collaboration/cancel',{'workflowId':first['id']})
        self.result('native/chat/queue/delete',{'threadId':key,'messageId':follow['id']})

    def test_resume_respects_task_attempt_limit_and_never_runs_followup_instead(self):
        self.configured();key=self.inline_chat(('codex','codex'))
        first=self.result('native/collaboration/session/submit',{'threadId':key,'clientRequestId':'limited-team','input':[{'type':'text','text':'wait-for-interrupt'}]})['workflow']
        doc=self.wait_workflow(first['id'],lambda d:all(t['status']=='running' for t in d['tasks']))
        self.control(key,'interrupt');self.interrupted(key)
        for t in doc['tasks']:
            self.result('native/collaboration/task/config',{'workflowId':first['id'],'taskId':t['id'],'config':{'maxAttempts':1}})
        self.assertIn('error',self.call('native/chat/execution/resume',{'threadId':key}))
        self.assertEqual(self.thread(key)['executionControl']['status'],'interrupted')
        self.assertEqual(len(self.thread(key)['turns']),1)

    def test_completed_task_cannot_be_stopped_or_replayed_by_empty_execution(self):
        key,turn=self.start(text='hello')
        self.until(lambda:self.thread(key),lambda d:d['turns'][0]['status']=='completed')
        self.assertIn('error',self.call('native/chat/execution/pause',{'threadId':key}))
        self.assertIn('error',self.call('native/chat/execution/resume',{'threadId':key}))
        self.assertEqual(len(self.thread(key)['turns']),1)

    def test_new_input_after_interrupt_runs_directly_instead_of_queueing(self):
        key,turn=self.start();self.control(key,'interrupt');self.interrupted(key)
        response=self.result('native/chat/turn/start',{'threadId':key,'input':[{'type':'text','text':'follow this new direction'}]})
        self.assertIn('turn',response);self.assertNotIn('entry',response)
        doc=self.until(lambda:self.thread(key),lambda d:len(d['turns'])==2 and d['turns'][-1]['status']=='completed')
        self.assertEqual(doc['executionControl']['status'],'superseded')
        self.assertEqual(doc['turns'][-1]['items'][0]['content'][0]['text'],'follow this new direction')
        self.assertFalse(any(e.get('executionResume') for e in self.entries(key)))
        self.assertFalse(any(e['status']=='queued' for e in self.entries(key)))

    def test_new_collaboration_input_after_interrupt_runs_directly(self):
        self.configured();key=self.inline_chat(('codex','codex'))
        first=self.result('native/collaboration/session/submit',{'threadId':key,'clientRequestId':'old-direct','input':[{'type':'text','text':'wait-for-interrupt'}]})['workflow']
        self.wait_workflow(first['id'],lambda d:all(t['status']=='running' for t in d['tasks']))
        self.control(key,'interrupt');self.interrupted(key)
        response=self.result('native/collaboration/session/submit',{'threadId':key,'clientRequestId':'new-direct','input':[{'type':'text','text':'direct next round'}]})
        self.assertIn('workflow',response);self.assertNotIn('entry',response)
        self.wait_workflow(response['workflow']['id'],lambda d:d['status']=='awaiting_acceptance')
        old=self.result('native/collaboration/read',{'workflowId':first['id']})['workflow']
        self.assertEqual(old['status'],'cancelled')
        self.assertEqual(len(self.thread(key)['turns']),2)

    def test_new_collaboration_input_after_interrupt_keeps_old_results_and_starts_next_round(self):
        self.configured();key=self.inline_chat(('codex','codex'))
        first=self.result('native/collaboration/session/submit',{'threadId':key,'clientRequestId':'old-team','input':[{'type':'text','text':'wait-for-interrupt'}]})['workflow']
        self.wait_workflow(first['id'],lambda d:all(t['status']=='running' for t in d['tasks']))
        self.control(key,'interrupt');self.interrupted(key)
        entry=self.queued(key,'work on this new team request','replacement','collaboration')
        entries=self.until(lambda:self.entries(key),lambda es:es[0]['status']=='sent')
        next_round=entries[0]['receipt']['workflow']
        self.assertNotEqual(next_round,first['id'])
        self.wait_workflow(next_round,lambda d:d['status']=='awaiting_acceptance')
        old=self.result('native/collaboration/read',{'workflowId':first['id']})['workflow']
        self.assertEqual(old['status'],'cancelled')
        self.assertTrue(all(len(t['attempts'])==1 for t in old['tasks']))
        self.assertTrue(all((self.folder/'state/collaboration/attempts'/t['attempts'][0]['id']).is_dir() for t in old['tasks']))
        self.assertEqual(len(self.thread(key)['turns']),2)

    def test_auto_followup_waits_for_resources_and_remains_editable(self):
        key,turn=self.start();entry=self.queued(key,'new direction')
        self.result('native/collaboration/resources',{'available':100})
        self.control(key,'interrupt')
        # The scheduler may supersede the stopped execution in the same tick;
        # assert the durable outcome instead of polling a transient control state.
        self.until(lambda:self.thread(key),lambda d:d['turns'][0]['status']=='interrupted')
        es=self.until(lambda:self.entries(key),lambda es:bool(es[0].get('waitReason')))
        self.assertEqual(es[0]['status'],'queued');self.assertFalse(es[0].get('executionResume'))
        self.assertEqual(len(self.thread(key)['turns']),1)
        self.result('native/chat/queue/edit',{'threadId':key,'messageId':entry['id'],'input':[{'type':'text','text':'edited direction'}]})
        self.result('native/collaboration/resources',{'available':8*1024**3})
        self.until(lambda:self.entries(key),lambda es:es[0]['status']=='sent')
        doc=self.until(lambda:self.thread(key),lambda d:len(d['turns'])==2 and d['turns'][-1]['status']=='completed')
        self.assertEqual(doc['turns'][-1]['items'][0]['content'][0]['text'],'edited direction')
        self.assertEqual(doc['executionControl']['status'],'superseded')


class ExecutionBudgetTests(unittest.TestCase):
    def test_previous_pause_state_is_migrated_to_interrupted_without_replay(self):
        from conversation_execution import ConversationExecution
        from types import SimpleNamespace
        from unittest.mock import Mock
        service=SimpleNamespace(queue=SimpleNamespace(live=set(),busy=lambda d:False))
        execution=ConversationExecution(service);execution.changed=Mock()
        doc={'id':'chat','turns':[{'id':'old','status':'interrupted'}],
             'executionControl':{'status':'paused','mode':'independent','turnId':'old'}}
        execution.refresh(doc)
        self.assertEqual(doc['executionControl']['status'],'interrupted')
        self.assertEqual(len(doc['turns']),1)
        execution.changed.assert_called_once_with(doc)

    def test_completion_winning_pause_race_does_not_offer_replay_or_block_queue(self):
        from conversation_execution import ConversationExecution
        from types import SimpleNamespace
        from unittest.mock import Mock
        service=SimpleNamespace(queue=SimpleNamespace(live=set(),busy=lambda d:False))
        execution=ConversationExecution(service);execution.changed=Mock()
        doc={'id':'chat','turns':[{'id':'old','status':'completed'}],
             'executionControl':{'status':'stopping','mode':'independent','turnId':'old'}}
        execution.refresh(doc)
        self.assertEqual(doc['executionControl']['status'],'completed')
        self.assertEqual(doc['executionControl']['finishedStatus'],'completed')
        execution.changed.assert_called_once_with(doc)

    def test_manual_pause_time_does_not_exhaust_execution_budget(self):
        import collaboration_policy as policy
        doc={'tasks':[],'startedAt':100,'status':'paused','executionPauseStartedAt':110,
             'executionPausedSeconds':5,'budget':{'maxRuntimeSeconds':20}}
        with patch('collaboration_policy.time.time',return_value=1000):
            metrics=policy.metrics(doc)
            self.assertEqual(metrics['elapsedSeconds'],5);self.assertEqual(metrics['stopReason'],'')
