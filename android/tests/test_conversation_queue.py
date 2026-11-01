"""Exercise durable queue and real adapter guidance over private pipes, without model APIs."""
import json
from pathlib import Path
import sys
import time
import threading
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import test_collaboration as existing

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from conversation_queue import ConversationQueue
from collaboration_store import CollaborationStore
from shared_conversations import SharedConversations
from worker_pool import WorkerPool


class QueueHostTests(unittest.TestCase):
    setUp=existing.CollaborationHostTests.setUp
    tearDown=existing.CollaborationHostTests.tearDown
    setUpClass=existing.CollaborationHostTests.setUpClass
    start_host=existing.CollaborationHostTests.start_host
    stop_host=existing.CollaborationHostTests.stop_host
    call=existing.CollaborationHostTests.call
    result=existing.CollaborationHostTests.result
    configured=existing.CollaborationHostTests.configured
    wait_workflow=existing.CollaborationHostTests.wait
    inline_chat=existing.CollaborationHostTests.inline_chat

    def until(self,fn,predicate,timeout=12):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=fn()
            if predicate(value):return value
            time.sleep(.05)
        self.fail('Timed out: '+str(value)[:3500])
    def thread(self,key):return self.result('native/chat/thread/read',{'threadId':key})['thread']
    def entries(self,key):return self.result('native/chat/queue/list',{'threadId':key})['entries']
    def queued(self,key,text,request='followup',mode='independent'):
        return self.result('native/chat/queue/submit',{'threadId':key,'input':[{'type':'text','text':text}],'mode':mode,'clientRequestId':request,'model':'test-model'})['entry']
    def start(self,engine='codex',text='wait-for-interrupt'):
        self.configured();key=self.result('native/chat/thread/start',{'engine':engine,'cwd':str(self.projects),'model':'test-model'})['thread']['id']
        turn=self.result('native/chat/turn/start',{'threadId':key,'engine':engine,'model':'test-model','input':[{'type':'text','text':text}]})['turn']
        self.until(lambda:(self.folder/'wire.jsonl').read_text(),lambda text:'session/prompt' in text if engine=='deepseek' else 'control_request' in text if engine=='claude' else 'turn/start' in text)
        time.sleep(.15)
        return key,turn
    def test_busy_send_queues_and_edit_delete_fifo_are_durable(self):
        key,turn=self.start()
        a=self.result('native/chat/turn/start',{'threadId':key,'clientRequestId':'a','input':[{'type':'text','text':'old direction'}]})['entry']
        b=self.queued(key,'must not execute','b');c=self.queued(key,'third direction','c')
        self.assertEqual(len(self.thread(key)['turns']),1)
        args={'threadId':key,'messageId':a['id'],'revision':a['revision'],'input':[{'type':'text','text':'new direction'}]}
        edited=self.result('native/chat/queue/edit',args)['entries'][0]
        self.assertGreater(edited['revision'],a['revision']);self.assertIn('error',self.call('native/chat/queue/edit',args))
        self.result('native/chat/queue/delete',{'threadId':key,'messageId':b['id']})
        self.result('native/chat/turn/interrupt',{'threadId':key,'turnId':turn['id']})
        entries=self.until(lambda:self.entries(key),lambda es:all(e['status'] in ('sent','deleted') for e in es))
        self.assertEqual([e['status'] for e in entries],['sent','deleted','sent'])
        self.until(lambda:self.thread(key),lambda d:len(d['turns'])==3 and d['turns'][-1]['status']=='completed')
        wire=(self.folder/'wire.jsonl').read_text();self.assertIn('new direction',wire);self.assertNotIn('old direction',wire);self.assertNotIn('must not execute',wire)
        turns=self.thread(key)['turns'];self.assertEqual(turns[1]['items'][0]['content'][0]['text'],'new direction')
        self.assertEqual(turns[2]['items'][0]['content'][0]['text'],'third direction')

    def test_immediate_retry_across_queue_endpoint_and_restart_never_duplicates(self):
        self.configured()
        key=self.result('native/chat/thread/start',{'engine':'codex','cwd':str(self.projects),'model':'test-model'})['thread']['id']
        args={'threadId':key,'engine':'codex','model':'test-model','clientRequestId':'direct-once',
              'input':[{'type':'text','text':'wait-for-interrupt'}]}
        first=self.result('native/chat/turn/start',args)['turn']
        for method in ('native/chat/turn/start','native/chat/queue/submit'):
            replay=self.result(method,args)
            self.assertTrue(replay['replayed']);self.assertEqual(replay['turn']['id'],first['id'])
        self.assertEqual(len(self.entries(key)),0)
        self.result('native/chat/turn/interrupt',{'threadId':key,'turnId':first['id']})
        self.until(lambda:self.thread(key),lambda d:d['turns'][0]['status']=='interrupted')
        self.stop_host();self.start_host();self.configured()
        replay=self.result('native/chat/turn/start',args)
        self.assertTrue(replay['replayed']);self.assertEqual(replay['turn']['id'],first['id'])
        self.assertEqual(len(self.thread(key)['turns']),1);self.assertEqual(self.entries(key),[])
        self.assertNotIn('normalRequests',replay['thread'])

    def test_completed_immediate_send_is_idempotent_for_every_agent(self):
        self.configured()
        for engine in ('codex','claude','deepseek'):
            with self.subTest(engine=engine):
                key=self.result('native/chat/thread/start',{'engine':engine,'cwd':str(self.projects),'model':'test-model'})['thread']['id']
                args={'threadId':key,'engine':engine,'model':'test-model','clientRequestId':'completed-'+engine,
                      'input':[{'type':'text','text':'hello once'}]}
                first=self.result('native/chat/turn/start',args)['turn']
                self.until(lambda:self.thread(key),lambda d:d['turns'][0]['status']=='completed')
                replay=self.result('native/chat/turn/start',args)
                self.assertTrue(replay['replayed']);self.assertEqual(replay['turn']['id'],first['id'])
                self.assertEqual(len(self.thread(key)['turns']),1);self.assertEqual(self.entries(key),[])

    def test_immediate_resource_placeholder_and_started_turn_share_one_receipt(self):
        self.configured();self.result('native/collaboration/resources',{'available':100})
        key=self.result('native/chat/thread/start',{'engine':'codex','cwd':str(self.projects),'model':'test-model'})['thread']['id']
        args={'threadId':key,'engine':'codex','model':'test-model','clientRequestId':'resource-once',
              'input':[{'type':'text','text':'hello after resource wait'}]}
        first=self.result('native/chat/turn/start',args)
        replay=self.result('native/chat/turn/start',args)
        self.assertTrue(replay['replayed']);self.assertEqual(replay['turn']['id'],first['turn']['id'])
        self.assertEqual(self.entries(key),[])
        self.result('native/collaboration/resources',{'available':8*1024**3})
        doc=self.until(lambda:self.thread(key),lambda d:len(d['turns'])==1 and d['turns'][0]['status']=='completed')
        replay=self.result('native/chat/turn/start',args)
        self.assertTrue(replay['replayed']);self.assertEqual(replay['turn']['id'],doc['turns'][0]['id'])
        self.assertNotEqual(replay['turn']['id'],first['turn']['id'])

    def test_submit_idempotence_and_delete_does_not_interrupt_active_task(self):
        key,turn=self.start();a=self.queued(key,'one');b=self.queued(key,'one')
        self.assertEqual(a['id'],b['id']);self.assertEqual(len(self.entries(key)),1)
        self.result('native/chat/queue/delete',{'threadId':key,'messageId':a['id']})
        self.assertEqual(self.thread(key)['turns'][0]['status'],'inProgress')
        self.assertEqual(self.queued(key,'one')['status'],'deleted')
        self.result('native/chat/turn/interrupt',{'threadId':key,'turnId':turn['id']})

    def test_all_three_independent_agents_include_in_same_turn_without_new_task(self):
        for engine in ('codex','claude','deepseek'):
            with self.subTest(engine=engine):
                key,turn=self.start(engine);entry=self.queued(key,'finish-after-guide: adjusted requirement',engine)
                self.result('native/chat/queue/include',{'threadId':key,'messageId':entry['id']})
                entries=self.until(lambda:self.entries(key),lambda es:es[0]['status'] in ('included','failed','needs_reconciliation'))
                self.assertEqual(entries[0]['status'],'included',entries)
                doc=self.until(lambda:self.thread(key),lambda d:d['turns'][0]['status']=='completed')
                self.assertEqual(len(doc['turns']),1);self.assertEqual(doc['turns'][0]['id'],turn['id'])
                self.assertTrue(any('adjusted requirement' in json.dumps(i) for i in doc['turns'][0]['items']))
        wire=[json.loads(line) for line in (self.folder/'wire.jsonl').read_text().splitlines()]
        requests=[r['message'] for r in wire if r.get('engine')=='deepseek' and 'message' in r]
        prompts=[r for r in requests if r.get('method')=='session/prompt']
        self.assertEqual(len(prompts),2);self.assertEqual(prompts[0]['params']['sessionId'],prompts[1]['params']['sessionId'])
        self.assertLess(next(i for i,r in enumerate(requests) if r.get('method')=='session/cancel'),next(i for i,r in enumerate(requests) if r is prompts[1]))

    def test_collaboration_busy_send_queues_next_round_and_edit_delete_work(self):
        self.configured();key=self.inline_chat(('codex','codex'))
        first=self.result('native/collaboration/session/submit',{'threadId':key,'clientRequestId':'first','input':[{'type':'text','text':'wait-for-interrupt'}]})['workflow']
        self.wait_workflow(first['id'],lambda d:all(t['status']=='running' for t in d['tasks']))
        entry=self.result('native/collaboration/session/submit',{'threadId':key,'clientRequestId':'next','input':[{'type':'text','text':'hello next round'}]})['entry']
        deleted=self.queued(key,'delete collaboration task','delete','collaboration');self.result('native/chat/queue/delete',{'threadId':key,'messageId':deleted['id']})
        self.assertEqual(entry['mode'],'collaboration');self.assertEqual(len(self.thread(key)['turns']),1)
        self.result('native/collaboration/cancel',{'workflowId':first['id']})
        self.until(lambda:self.entries(key),lambda es:es[0]['status']=='sent')
        second=self.entries(key)[0]['receipt']['workflow'];self.wait_workflow(second,lambda d:d['status']=='awaiting_acceptance')
        self.assertEqual(len(self.thread(key)['turns']),2)

    def test_collaboration_includes_live_members_and_does_not_create_attempts(self):
        self.configured();key=self.inline_chat(('codex','claude','deepseek'))
        self.result('native/collaboration/settings',{'parallel':3,'sourceParallel':3})
        first=self.result('native/collaboration/session/submit',{'threadId':key,'clientRequestId':'live','input':[{'type':'text','text':'wait-for-interrupt'}]})['workflow']
        self.wait_workflow(first['id'],lambda d:all(t['status']=='running' and t['attempts'] and t['attempts'][-1]['events'] for t in d['tasks']))
        entry=self.queued(key,'finish-after-guide: switch direction','guidance','collaboration')
        self.result('native/chat/queue/include',{'threadId':key,'messageId':entry['id']})
        entries=self.until(lambda:self.entries(key),lambda es:es[0]['status'] in ('included','failed','needs_reconciliation'))
        self.assertEqual(entries[0]['status'],'included',entries)
        self.assertEqual({r['engine'] for r in entries[0]['receipts']},{'codex','claude','deepseek'})
        doc=self.wait_workflow(first['id'],lambda d:d['status']=='awaiting_acceptance')
        self.assertTrue(all(len(t['attempts'])==1 for t in doc['tasks']))
        self.assertEqual(doc['messages'][0]['id'],entry['id']);self.assertEqual(len(self.thread(key)['turns']),1)
        self.assertTrue(any('switch direction' in json.dumps(i) for i in self.thread(key)['turns'][0]['items'] if i.get('type')=='userMessage'))

    def test_restart_retains_queue_but_waits_for_explicit_resume(self):
        key,turn=self.start();entry=self.queued(key,'hello after restart')
        self.result('native/chat/turn/interrupt',{'threadId':key,'turnId':turn['id']})
        self.result('native/chat/queue/pause',{'threadId':key})
        self.stop_host();self.start_host();time.sleep(.4)
        self.assertTrue(self.result('native/chat/queue/list',{'threadId':key})['paused']);self.assertEqual(self.entries(key)[0]['status'],'queued')
        self.configured();self.result('native/chat/queue/resume',{'threadId':key})
        self.until(lambda:self.entries(key),lambda es:es[0]['status']=='sent')

    def test_failed_include_does_not_silently_become_a_new_turn(self):
        key,turn=self.start();self.result('native/chat/queue/pause',{'threadId':key});entry=self.queued(key,'do not dispatch')
        self.result('native/chat/turn/interrupt',{'threadId':key,'turnId':turn['id']})
        self.until(lambda:self.thread(key),lambda d:d['turns'][0]['status']=='interrupted')
        self.assertIn('error',self.call('native/chat/queue/include',{'threadId':key,'messageId':entry['id']}))
        self.assertEqual(self.entries(key)[0]['status'],'queued');self.assertEqual(len(self.thread(key)['turns']),1)

    def test_queued_attachment_survives_edit(self):
        key,turn=self.start();parts=[{'type':'text','text':'old'},{'type':'localImage','path':str(self.folder/'image.png')}]
        entry=self.result('native/chat/queue/submit',{'threadId':key,'input':parts,'clientRequestId':'image'})['entry']
        parts[0]['text']='new';self.result('native/chat/queue/edit',{'threadId':key,'messageId':entry['id'],'input':parts})
        self.assertEqual(self.entries(key)[0]['input'][1],parts[1]);self.result('native/chat/queue/delete',{'threadId':key,'messageId':entry['id']})
        self.result('native/chat/turn/interrupt',{'threadId':key,'turnId':turn['id']})

    def test_include_retry_does_not_deliver_same_message_twice(self):
        key,turn=self.start();entry=self.queued(key,'continue with additional info')
        args={'threadId':key,'messageId':entry['id']}
        self.result('native/chat/queue/include',args)
        self.until(lambda:self.entries(key),lambda es:es[0]['status']=='included')
        self.result('native/chat/queue/include',args)
        records=[json.loads(line) for line in (self.folder/'wire.jsonl').read_text().splitlines()]
        self.assertEqual(sum(r.get('message',{}).get('method')=='turn/steer' for r in records),1)
        self.result('native/chat/turn/interrupt',{'threadId':key,'turnId':turn['id']})

    def test_selected_collaboration_member_only_receives_guidance(self):
        self.configured();key=self.inline_chat(('codex','codex'))
        first=self.result('native/collaboration/session/submit',{'threadId':key,'clientRequestId':'target','input':[{'type':'text','text':'wait-for-interrupt'}]})['workflow']
        doc=self.wait_workflow(first['id'],lambda d:all(t['status']=='running' and t['attempts'] and t['attempts'][-1]['events'] for t in d['tasks']))
        entry=self.queued(key,'finish-after-guide: targeted information','selected','collaboration')
        target=doc['tasks'][0]['id'];other=doc['tasks'][1]['id']
        self.result('native/chat/queue/include',{'threadId':key,'messageId':entry['id'],'taskIds':[target]})
        entries=self.until(lambda:self.entries(key),lambda es:es[0]['status']=='included')
        self.assertEqual([r['taskId'] for r in entries[0]['receipts']],[target])
        doc=self.wait_workflow(first['id'],lambda d:d['tasks'][0]['status']=='succeeded');self.assertEqual(doc['tasks'][1]['status'],'running')
        handoff=self.result('native/collaboration/task/handoff',{'workflowId':first['id'],'taskId':other})['handoff']
        self.assertEqual(handoff['requirements'],[])
        self.result('native/collaboration/cancel',{'workflowId':first['id']})

    def test_queue_starts_after_running_task_finishes_normally(self):
        key,turn=self.start()
        guide=self.queued(key,'finish-after-guide: finish the original task','guide')
        follow=self.queued(key,'after normal completion','follow')
        time.sleep(.25)
        self.result('native/chat/queue/include',{'threadId':key,'messageId':guide['id']})
        self.until(lambda:self.entries(key),lambda es:es[0]['status']=='included')
        self.until(lambda:self.thread(key),lambda d:d['turns'][0]['status']=='completed')
        entries=self.until(lambda:self.entries(key),lambda es:es[1]['status']=='sent')
        self.assertEqual(entries[1]['receipt'].get('turn') is not None,True)
        doc=self.until(lambda:self.thread(key),lambda d:len(d['turns'])==2 and d['turns'][-1]['status']=='completed')
        self.assertEqual(doc['turns'][1]['items'][0]['content'][0]['text'],'after normal completion')

    def test_clean_queue_auto_resumes_after_service_restart(self):
        key,turn=self.start(text='hello')
        self.until(lambda:self.thread(key),lambda d:d['turns'][0]['status']=='completed')
        entry=self.queued(key,'after clean restart')
        self.stop_host();self.start_host();self.configured();time.sleep(.4)
        listing=self.result('native/chat/queue/list',{'threadId':key})
        self.assertFalse(listing['paused'])
        self.until(lambda:self.entries(key),lambda es:es[0]['status']=='sent')
        self.until(lambda:self.thread(key),lambda d:len(d['turns'])==2 and d['turns'][-1]['status']=='completed')

    def test_queue_remains_editable_when_waiting_for_resources(self):
        self.configured();self.result('native/collaboration/resources',{'available':100})
        key=self.result('native/chat/thread/start',{'engine':'codex','cwd':str(self.projects),'model':'test-model'})['thread']['id']
        entry=self.queued(key,'hello after resources')
        es=self.until(lambda:self.entries(key),lambda es:bool(es[0].get('waitReason')))
        self.assertEqual(es[0]['status'],'queued');self.assertEqual(len(self.thread(key)['turns']),0)
        self.result('native/chat/queue/edit',{'threadId':key,'messageId':entry['id'],'input':[{'type':'text','text':'edited after resources'}]})
        self.result('native/collaboration/resources',{'available':8*1024**3})
        self.until(lambda:self.entries(key),lambda es:es[0]['status']=='sent')
        self.until(lambda:self.thread(key),lambda doc:len(doc['turns'])==1 and doc['turns'][0]['status']=='completed')

    def test_guidance_clears_obsolete_collaboration_approval(self):
        self.stop_host();self.env['AI_XS_TEST_MULTIMODEL']='1';self.start_host();self.configured();key=self.inline_chat(('claude','deepseek'))
        first=self.result('native/collaboration/session/submit',{'threadId':key,'clientRequestId':'approval-guidance','input':[{'type':'text','text':'permission'}]})['workflow']
        self.wait_workflow(first['id'],lambda d:all(t['status']=='waiting_input' for t in d['tasks']))
        entry=self.queued(key,'finish-after-guide: continue differently','approval-queue','collaboration')
        self.result('native/chat/queue/include',{'threadId':key,'messageId':entry['id']})
        entries=self.until(lambda:self.entries(key),lambda es:es[0]['status'] in ('included','failed','needs_reconciliation'))
        self.assertEqual(entries[0]['status'],'included',entries)
        doc=self.wait_workflow(first['id'],lambda d:d['status']=='awaiting_acceptance')
        self.assertTrue(all(not t.get('approvals') for t in doc['tasks']))


class QueueRecoveryTests(unittest.TestCase):
    def test_legacy_user_pause_is_preserved_and_migrated_across_restarts(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp);chats=SharedConversations(folder/'chats',None,None,None,lambda *args:'ask')
            doc=chats.adopt(chats.handle('thread/start',{'cwd':temp})['thread']['id'])
            store=CollaborationStore(folder/'workflows')
            s=SimpleNamespace(chats=chats,store=store,emit=lambda event:None,lock=threading.RLock(),normal_active=set())
            try:
                queue=ConversationQueue(s)
                queue.submit(doc,{'input':[{'type':'text','text':'keep paused'}]},'independent')
                doc['messageQueuePaused']=True;doc.pop('messageQueuePauseReason',None);chats._save(doc)
                for _ in range(2):
                    queue=ConversationQueue(s)
                    self.assertTrue(doc['messageQueuePaused']);self.assertEqual(doc['messageQueuePauseReason'],'user')
                queue.handle('resume',{'threadId':doc['id']})
                ConversationQueue(s);self.assertFalse(doc['messageQueuePaused'])
            finally:store.close()

    def test_unresolved_immediate_receipt_survives_restart_without_resubmitting(self):
        import tempfile
        from unittest.mock import Mock
        from collaboration_service import CollaborationService
        with tempfile.TemporaryDirectory() as temp:
            chats=SharedConversations(Path(temp)/'chats',None,None,None,lambda *args:'ask')
            doc=chats.adopt(chats.handle('thread/start',{'cwd':temp})['thread']['id'])
            service=CollaborationService.__new__(CollaborationService);service.chats=chats
            args={'threadId':doc['id'],'clientRequestId':'lost-response','input':[{'type':'text','text':'do once'}]}
            with patch.object(chats,'handle',Mock(side_effect=TimeoutError('receipt lost'))) as send:
                with self.assertRaises(TimeoutError):service._normal_start(doc,'turn/start',args)
                with self.assertRaisesRegex(ValueError,'核对'):service.normal_replay(doc,args)
                self.assertEqual(send.call_count,1)
            service.chats=SharedConversations(Path(temp)/'chats',None,None,None,lambda *args:'ask')
            with self.assertRaisesRegex(ValueError,'核对'):
                service.normal_replay(service.chats.adopt(doc['id']),args)

    def test_dispatch_timeout_requires_reconciliation_and_never_replays(self):
        import tempfile
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp);chats=SharedConversations(folder/'chats',None,None,None,lambda *args:'ask')
            doc=chats.adopt(chats.handle('thread/start',{'cwd':temp})['thread']['id'])
            store=CollaborationStore(folder/'workflows')
            s=SimpleNamespace(chats=chats,store=store,emit=lambda event:None,lock=threading.RLock(),
                normal_active=set(),closed=threading.Event(),scheduler=SimpleNamespace(closed=False,reason=lambda *args,**kw:None),
                normal_submit=Mock(side_effect=TimeoutError('submission receipt lost')))
            try:
                queue=ConversationQueue(s)
                entry=queue.submit(doc,{'input':[{'type':'text','text':'do once'}]},'independent')['entry']
                with patch.object(queue,'start_thread',side_effect=lambda fn,name:fn()):
                    queue.tick();queue.tick()
                self.assertEqual(s.normal_submit.call_count,1)
                state=queue.handle('list',{'threadId':doc['id']})['entries'][0]
                self.assertEqual(state['status'],'needs_reconciliation');self.assertEqual(state['input'],entry['input'])
                with self.assertRaisesRegex(ValueError,'核对'):
                    queue.handle('retry',{'threadId':doc['id'],'messageId':entry['id']})
            finally:store.close()

    def test_worker_unknown_receipt_preserves_timeout_instead_of_rejection(self):
        pool=WorkerPool.__new__(WorkerPool);pool.lock=threading.RLock()
        entry={'stopping':False,'lock':threading.Lock(),'controls':{}}
        def acknowledge():
            pending=next(iter(entry['controls'].values()))
            pending['response']={'ok':False,'unknown':True,'error':'member lost receipt'};pending['done'].set()
        entry['process']=SimpleNamespace(stdin=SimpleNamespace(write=lambda value:None,flush=acknowledge))
        pool.live={'attempt':entry}
        with self.assertRaisesRegex(TimeoutError,'lost receipt'):pool.steer('attempt',{'input':[]})
        self.assertEqual(entry['controls'],{})

    def test_unknown_delivery_requires_reconciliation_after_restart_and_retains_input(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp);chats=SharedConversations(folder/'chats',None,None,None,lambda *args:'ask')
            doc=chats.adopt(chats.handle('thread/start',{'cwd':temp})['thread']['id'])
            for i,state in enumerate(('queued','dispatching','including')):
                doc.setdefault('messageQueue',[]).append({'id':str(i),'clientRequestId':str(i),'revision':1,'status':state,'input':[{'type':'text','text':'keep '+str(i)}]})
            doc['executionControl']={'status':'resuming','resumeMessageId':'1'}
            chats._save(doc);store=CollaborationStore(folder/'workflows')
            s=SimpleNamespace(chats=chats,store=store,emit=lambda event:None,lock=threading.RLock())
            try:
                queue=ConversationQueue(s);self.assertTrue(doc['messageQueuePaused'])
                self.assertEqual(doc['executionControl']['status'],'needs_reconciliation')
                self.assertEqual([e['status'] for e in doc['messageQueue']],['queued','needs_reconciliation','needs_reconciliation'])
                args={'threadId':doc['id'],'messageId':'1'}
                with self.assertRaisesRegex(ValueError,'核对'):queue.handle('retry',args)
                self.assertEqual(doc['messageQueue'][1]['input'][0]['text'],'keep 1')
                queue.handle('retry',dict(args,confirmed=True));self.assertEqual(doc['messageQueue'][1]['status'],'queued')
            finally:store.close()
