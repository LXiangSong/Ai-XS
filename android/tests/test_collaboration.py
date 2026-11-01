"""Concurrency, isolation, recovery and dependency tests for native collaboration."""
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from collaboration_service import validate_plan
from collaboration_store import CollaborationStore
from task_scheduler import TaskScheduler
import collaboration_workspace as workspace
import test_native_host as native


class SchedulerTests(unittest.TestCase):
    def test_unreserved_builtin_tools_are_rejected_before_network_access(self):
        from collaboration_mcp import forward
        config={'url':'http://127.0.0.1:1','context':{'attemptId':'one'}}
        with patch('collaboration_mcp.urllib.request.urlopen') as network:
            for name in ('phone_click','browser_evaluate','extensions_install'):
                with self.subTest(tool=name),self.assertRaises(ValueError):
                    forward(config,{'id':1,'method':'tools/call','params':{'name':name,'arguments':{}}})
            network.assert_not_called()
    def test_gateway_cap_and_stale_resource_sample(self):
        s=TaskScheduler(lambda:{'parallel':3,'sourceParallel':1},lambda:{'count':0,'projects':[]},lambda e:True)
        s.resources({'available':4*1024**3})
        self.assertEqual(s.reserve('one','/one','codex',group='gateway'),'')
        self.assertIn('API',s.reserve('two','/two','claude',group='gateway'))
        self.assertEqual(s.reserve('two','/two','claude',group='different'),'')
        s.sample['receivedAt']-=21
        self.assertIn('资源信息',s.reserve('three','/three','deepseek',group='third'))

    def test_registration_failure_cleans_up_started_worker(self):
        from worker_pool import WorkerPool
        from execution_profiles import ExecutionProfiles
        from provider_manager import ProviderManager
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp);providers=ProviderManager(folder/'providers',folder,folder)
            providers.save('codex',{'mode':'thirdparty','baseUrl':'https://gateway.invalid/v1','key':'test-private','models':['test-model']})
            class Mcp:
                def effective(self,engine):return {}
            pool=WorkerPool(folder/'attempts',ExecutionProfiles(providers,lambda e:True),Mcp(),None,lambda e:None)
            started=[];popen=subprocess.Popen
            def launch(*args,**kwargs):
                p=popen([sys.executable,'-c','import time;time.sleep(60)'],**kwargs);started.append(p);return p
            def registered(*args):raise ValueError('registration failed')
            with patch('worker_pool.subprocess.Popen',side_effect=launch):
                with self.assertRaisesRegex(ValueError,'registration failed'):
                    pool.run('attempt',{'engine':'codex','model':'test-model'},str(folder),'hello',{'context':{}},lambda *a:None,registered)
            self.assertEqual(pool.live,{})
            self.assertIsNotNone(started[0].poll())

    def test_global_heavy_project_and_device_limits(self):
        external={'count':0,'projects':[]}
        s=TaskScheduler(lambda:{'parallel':2},lambda:external,lambda e:True)
        self.assertIn('资源信息',s.reserve('a','/one','codex'))
        s.resources({'available':4*1024**3})
        self.assertEqual(s.reserve('a','/one','codex',heavy=True,device=True),'')
        self.assertIn('重任务',s.reserve('b','/two','claude',heavy=True))
        self.assertIn('手机',s.reserve('b','/two','claude',device=True))
        self.assertIn('同项目',s.reserve('b','/one','claude',normal=True))
        self.assertEqual(s.reserve('b','/two','codex'),'')
        self.assertIn('名额',s.reserve('c','/three','deepseek'))
        s.release('b');external['count']=1
        self.assertIn('名额',s.reserve('c','/three','deepseek'))
        external['count']=0;s.resources({'available':4*1024**3,'thermal':4})
        self.assertIn('过热',s.reserve('c','/three','deepseek'))

    def test_plan_validation_rejects_cycles_and_unknown_assignments(self):
        p=[{'id':'a'}];t=[{'id':'1','participantId':'a','goal':'one','acceptance':'result','dependencies':['2']},
                           {'id':'2','participantId':'a','goal':'two','acceptance':'result','dependencies':['1']}]
        with self.assertRaisesRegex(ValueError,'循环'):validate_plan(p,t,'a')
        t[1]['dependencies']=[];validate_plan(p,t,'a')
        t[0]['participantId']='b'
        with self.assertRaisesRegex(ValueError,'分配'):validate_plan(p,t,'a')

    def test_store_idempotence_and_revision(self):
        with tempfile.TemporaryDirectory() as folder:
            s=CollaborationStore(folder)
            try:
                d=s.create({'goal':'one'},'same');self.assertEqual(s.create({'goal':'two'},'same')['id'],d['id'])
                s.update(d['id'],lambda d:d.update(goal='changed'),1)
                with self.assertRaisesRegex(ValueError,'变更'):s.update(d['id'],lambda d:None,1)
            finally:s.close()

    def test_worktree_never_changes_original_and_integration_checks_baseline(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'repo';source.mkdir()
            for args in [('init',),('config','user.email','test@example.invalid'),('config','user.name','Test')]:workspace.git(source,*args)
            (source/'a.txt').write_text('before\n');workspace.git(source,'add','.');workspace.git(source,'commit','-m','initial')
            w=workspace.prepare(source,root/'worker',[]);(Path(w['path'])/'a.txt').write_text('after\n')
            artifact=workspace.capture(w,root);self.assertEqual((source/'a.txt').read_text(),'before\n')
            workspace.git(w['path'],'add','.');workspace.git(w['path'],'commit','-m','worker commit')
            artifact=workspace.capture(w,root)
            self.assertTrue(Path(artifact['patch']).stat().st_size,'Agent commits must remain in the delivery patch')
            (source/'local.txt').write_text('user work')
            with self.assertRaisesRegex(ValueError,'基线'):workspace.integrate(artifact)
            (source/'local.txt').unlink();workspace.integrate(artifact);self.assertEqual((source/'a.txt').read_text(),'after\n')


class CollaborationHostTests(unittest.TestCase):
    setUp=native.NativeHostTests.setUp
    tearDown=native.NativeHostTests.tearDown
    start_host=native.NativeHostTests.start_host
    stop_host=native.NativeHostTests.stop_host
    call=native.NativeHostTests.call
    result=native.NativeHostTests.result
    setUpClass=native.NativeHostTests.setUpClass

    def configured(self):
        for e,protocol in [('codex','responses'),('claude','anthropic-messages'),('deepseek','anthropic-messages')]:
            self.result('provider/save',{'engine':e,'mode':'thirdparty','name':'Local','baseUrl':'https://gateway.invalid',
                'protocol':protocol,'models':['test-model','second-model'],'key':'private-'+e})
        self.result('native/collaboration/resources',{'available':8*1024**3,'thermal':0})

    def create(self,engines=('codex','codex','claude'),goal='wait-for-interrupt',dependencies=False):
        p=[{'id':'p'+str(i),'engine':e,'name':e+str(i),'model':'test-model' if i%2==0 else 'second-model','permission':'ask'} for i,e in enumerate(engines)]
        tasks=[{'id':'t'+str(i),'title':'Task '+str(i),'goal':goal,'acceptance':'text result','participantId':'p'+str(i),
                'dependencies':['t0'] if dependencies and i else []} for i in range(len(engines))]
        return self.result('native/collaboration/create',{'clientRequestId':os.urandom(8).hex(),'cwd':str(self.projects),
            'goal':'Test workflow','participants':p,'tasks':tasks,'leadParticipantId':'p0'})['workflow']

    def wait(self,w,predicate,timeout=12):
        until=time.monotonic()+timeout
        while time.monotonic()<until:
            d=self.result('native/collaboration/read',{'workflowId':w})['workflow']
            if predicate(d):return d
            time.sleep(.08)
        self.fail('Workflow timed out: '+json.dumps(d,ensure_ascii=False)[:4000])

    def test_two_same_agent_models_overlap_third_queues_and_stop_is_isolated(self):
        self.configured();d=self.create();key=d['id']
        self.result('native/collaboration/start',{'workflowId':key})
        d=self.wait(key,lambda d:sum(t['status']=='running' for t in d['tasks'])==2)
        self.assertEqual(d['tasks'][2]['status'],'queued');self.assertEqual(d['tasks'][0]['attempts'][0]['config']['model'],'test-model')
        self.assertEqual(d['tasks'][1]['attempts'][0]['config']['model'],'second-model')
        self.assertIn('共享会话',self.call('turn/start',{'threadId':'raw','input':[{'type':'text','text':'hello'}]})['error'])
        self.result('native/collaboration/pause',{'workflowId':key})
        self.result('native/collaboration/stop',{'workflowId':key,'taskId':'t0'})
        d=self.wait(key,lambda d:d['tasks'][0]['status']=='interrupted')
        self.assertEqual(d['tasks'][1]['status'],'running')
        self.assertEqual(self.result('provider/read',{'engine':'codex'})['profiles']['thirdparty']['models'],['test-model','second-model'])
        self.result('native/collaboration/cancel',{'workflowId':key});self.wait(key,lambda d:not any(t['status'] in ('running','stopping','starting') for t in d['tasks']))
        self.result('native/collaboration/retry',{'workflowId':key,'taskId':'t0'})
        d=self.result('native/collaboration/read',{'workflowId':key})['workflow']
        self.assertEqual(d['status'],'paused');self.assertEqual(d['tasks'][0]['status'],'queued')
        self.result('native/collaboration/resume',{'workflowId':key})
        self.wait(key,lambda d:d['tasks'][0]['status']=='running')
        self.result('native/collaboration/cancel',{'workflowId':key})

    def test_duplicate_creation_and_invalid_planner_keep_original_draft(self):
        self.configured();d=self.create(('codex',),'hello');key=d['id']
        before=self.result('native/threads/list',{})['total']
        request='same-request'
        payload={'clientRequestId':request,'cwd':str(self.projects),'goal':'another',
                 'participants':d['participants'],'tasks':d['tasks'],'leadParticipantId':d['leadParticipantId']}
        first=self.result('native/collaboration/create',payload)['workflow']
        again=self.result('native/collaboration/create',payload)['workflow']
        self.assertEqual(first['conversationId'],again['conversationId'])
        self.assertEqual(self.result('native/threads/list',{})['total'],before+1)
        self.result('native/collaboration/plan',{'workflowId':key})
        d=self.wait(key,lambda d:d['status']=='draft' and d.get('planningHistory'))
        self.assertIn('原草稿已保留',d['planError']);self.assertEqual(d['tasks'][0]['id'],'t0')

    def test_running_provider_snapshot_is_immutable_and_not_in_public_records(self):
        self.configured();d=self.create(('codex',),'wait-for-interrupt');key=d['id']
        self.result('native/collaboration/start',{'workflowId':key})
        d=self.wait(key,lambda d:d['tasks'][0]['status']=='running')
        attempt=d['tasks'][0]['attempts'][0];revision=attempt['config']['providerRevision']
        self.result('provider/save',{'engine':'codex','mode':'thirdparty','baseUrl':'https://new.invalid/v1',
            'key':'replacement-private','models':['second-model']})
        profile=json.loads((self.folder/'state'/'collaboration'/'attempts'/attempt['id']/'providers.json').read_text())
        selected=profile['engines']['codex']['profiles']['thirdparty']
        self.assertEqual(selected['key'],'private-codex');self.assertEqual(selected['models'],['test-model'])
        d=self.result('native/collaboration/read',{'workflowId':key})['workflow']
        self.assertEqual(d['tasks'][0]['attempts'][0]['config']['providerRevision'],revision)
        self.assertNotIn('private-codex',json.dumps(d))
        self.result('native/collaboration/cancel',{'workflowId':key})

    def test_three_engines_dependencies_acceptance_and_same_conversation(self):
        self.configured();d=self.create(('codex','claude','deepseek'),'hello',True);key=d['id']
        self.result('native/collaboration/start',{'workflowId':key})
        d=self.wait(key,lambda d:d['status'] in ('awaiting_acceptance','partial_failure'),20)
        self.assertEqual(d['status'],'awaiting_acceptance',d)
        self.assertTrue(all(t['result'] for t in d['tasks']))
        end=d['tasks'][0]['attempts'][0]['finishedAt']
        self.assertTrue(all(t['attempts'][0]['startedAt']>=end for t in d['tasks'][1:]))
        self.result('native/collaboration/accept',{'workflowId':key})
        t=self.result('native/chat/thread/read',{'threadId':d['conversationId']})['thread']
        self.assertEqual(t['id'],d['conversationId']);self.assertIn('协作成果',t['turns'][-1]['items'][0]['text'])
        self.stop_host();self.start_host();d=self.result('native/collaboration/read',{'workflowId':key})['workflow']
        self.assertTrue(d['accepted']);self.assertEqual(len(d['tasks'][0]['attempts']),1)

    def test_collaboration_conversation_preserves_three_engine_sources_and_tools_after_restart(self):
        self.configured();d=self.create(('codex','claude','deepseek'),'hello');key=d['id']
        self.result('native/collaboration/start',{'workflowId':key})
        d=self.wait(key,lambda d:d['status']=='awaiting_acceptance',20)
        before=self.result('native/collaboration/conversation',{'workflowId':key})
        entries=before['entries'];self.assertTrue(entries)
        self.assertEqual({e['engine'] for e in entries},{'codex','claude','deepseek'})
        self.assertTrue(any(e.get('item',{}).get('type')=='commandExecution' for e in entries))
        self.assertTrue(all(e.get('item',{}).get('type')!='userMessage' for e in entries))
        self.assertFalse(before['historyPartial'])
        self.assertNotIn('private-codex',json.dumps(entries))
        self.stop_host();self.start_host()
        after=self.result('native/collaboration/conversation',{'workflowId':key})
        self.assertEqual(entries,after['entries'])
        filtered=self.result('native/collaboration/conversation',{'workflowId':key,'participantId':'p1'})
        self.assertTrue(filtered['entries']);self.assertTrue(all(e['engine']=='claude' for e in filtered['entries']))

    def inline_chat(self,engines=('codex','claude','deepseek')):
        tid=self.result('native/chat/thread/start',{'engine':'codex','cwd':str(self.projects)})['thread']['id']
        people=[{'id':'p'+str(i),'engine':e,'name':e+str(i),'model':'test-model' if i%2==0 else 'second-model','role':'职责 '+str(i),'permission':'ask'} for i,e in enumerate(engines)]
        self.result('native/collaboration/session/config',{'threadId':tid,'mode':'collaboration','participants':people,'summarize':False})
        return tid

    def test_inline_streams_have_attempt_sequences_and_match_live_snapshots(self):
        self.configured();tid=self.inline_chat()
        response=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'live-events',
            'input':[{'type':'text','text':'progress-demo'}]})
        key=response['workflow']['id'];seen={};until=time.monotonic()+20
        def capture(e):
            if e.get('workflowId')!=key:return
            self.assertEqual(e.get('conversationId'),tid);self.assertGreater(e.get('attemptSequence',0),0)
            p=e.get('params',{});engine=e['engine'];method=e.get('method')
            ready=(engine=='codex' and method=='item/commandExecution/outputDelta' or
                engine=='claude' and method=='item/started' and p.get('item',{}).get('elapsedSeconds')==2 or
                engine=='deepseek' and method=='item/started' and bool(p.get('item',{}).get('result')))
            if ready and engine not in seen:seen[engine]=e
        for e in self.events:capture(e)
        while len(seen)<3 and time.monotonic()<until:
            e=self.messages.get(timeout=max(.1,until-time.monotonic())).get('event',{})
            self.events.append(e);capture(e)
        self.assertEqual(set(seen),{'codex','claude','deepseek'})
        for e in seen.values():
            index=self.events.index(e)
            self.assertFalse(any(x.get('attemptId')==e['attemptId'] and x.get('method')=='turn/completed' for x in self.events[:index]))
        live=self.result('native/collaboration/session/read',{'threadId':tid})['thread']['turns'][-1]
        for e in seen.values():self.assertGreaterEqual(live['collaborationRevisions'][e['attemptId']],e['attemptSequence'])
        self.wait(key,lambda d:d['status']=='awaiting_acceptance',20)
        final=self.result('native/collaboration/session/read',{'threadId':tid})['thread']['turns'][-1]
        self.assertEqual(final['status'],'completed')
        for e in seen.values():
            group=[i for i in final['items'] if i.get('attemptId')==e['attemptId']]
            self.assertTrue(group);self.assertTrue(all(i['id'].startswith(e['attemptId']+':') for i in group))
            self.assertTrue(all(i['engine']==e['engine'] and i['model']==e['model'] for i in group))
            seq=[x['attemptSequence'] for x in self.events if x.get('attemptId')==e['attemptId']]
            self.assertEqual(seq,sorted(set(seq)))

    def test_inline_modes_share_one_conversation_transcript_history_and_usage(self):
        self.configured();tid=self.inline_chat()
        result=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'inline-message','input':[{'type':'text','text':'hello shared conversation'}]})
        key=result['workflow']['id'];self.assertEqual(result['thread']['id'],tid)
        self.wait(key,lambda d:d['status']=='awaiting_acceptance',20)
        before=self.result('native/collaboration/session/read',{'threadId':tid})['thread']
        self.assertEqual(len(before['turns']),1)
        turn=before['turns'][0];self.assertEqual(turn['workflowId'],key)
        self.assertEqual(sum(i['type']=='userMessage' for i in turn['items']),1)
        self.assertEqual({i['engine'] for i in turn['items'] if i['type']=='agentMessage'},{'codex','claude','deepseek'})
        self.assertTrue(any(i['type']=='commandExecution' for i in turn['items']))
        self.assertNotIn('团队分工',json.dumps(turn,ensure_ascii=False))
        repeated=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'inline-message','input':[{'type':'text','text':'hello shared conversation'}]})
        self.assertEqual(repeated['workflow']['id'],key);self.assertEqual(len(repeated['thread']['turns']),1)
        switched=self.result('native/collaboration/session/config',{'threadId':tid,'mode':'independent'})['thread']
        self.assertEqual(switched['id'],tid);self.assertEqual(switched['turns'],before['turns'])
        self.assertEqual(switched['usage']['tokenUsage'],before['usage']['tokenUsage'])
        normal=self.result('native/chat/turn/start',{'threadId':tid,'engine':'codex','input':[{'type':'text','text':'continue independently'}]})
        until=time.monotonic()+10
        while time.monotonic()<until:
            thread=self.result('native/chat/thread/read',{'threadId':tid})['thread']
            if len(thread['turns'])==2 and thread['turns'][-1]['status']=='completed':break
            time.sleep(.1)
        self.assertEqual(thread['id'],tid);self.assertEqual(len(thread['turns']),2)
        again=self.result('native/collaboration/session/config',{'threadId':tid,'mode':'collaboration'})['thread']
        self.assertEqual(again['turns'],thread['turns'])
        self.assertEqual(len(again['collaborationParticipants']),3)
        self.stop_host();self.start_host()
        restored=self.result('native/collaboration/session/read',{'threadId':tid})['thread']
        self.assertEqual(restored['id'],tid);self.assertEqual(restored['executionMode'],'collaboration')
        self.assertEqual(restored['turns'],again['turns'])

    def test_inline_mode_switch_during_work_and_stop_preserve_pending_independent_message(self):
        self.configured();tid=self.inline_chat(('codex','codex'))
        result=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'inline-wait','input':[{'type':'text','text':'wait-for-interrupt'}]})
        key=result['workflow']['id'];self.wait(key,lambda d:sum(t['status']=='running' for t in d['tasks'])==2)
        switched=self.result('native/collaboration/session/config',{'threadId':tid,'mode':'independent'})['thread']
        self.assertEqual(switched['turns'][0]['collaborationStatus'],'running')
        queued=self.result('native/chat/turn/start',{'threadId':tid,'engine':'codex','input':[{'type':'text','text':'after collaborative stop'}]})
        self.assertTrue(queued['queued'])
        self.result('native/collaboration/cancel',{'workflowId':key})
        self.wait(key,lambda d:not any(t['status'] in ('starting','running','stopping') for t in d['tasks']))
        until=time.monotonic()+10
        while time.monotonic()<until:
            thread=self.result('native/collaboration/session/read',{'threadId':tid})['thread']
            if len(thread['turns'])==2 and thread['turns'][-1]['status']=='completed':break
            time.sleep(.1)
        self.assertEqual(thread['id'],tid);self.assertEqual(len(thread['turns']),2)
        self.assertEqual(thread['turns'][0]['collaborationStatus'],'cancelled')
        self.assertEqual(thread['turns'][-1]['status'],'completed')

    def test_usage_all_models_keeps_retired_models_and_stats_across_restart(self):
        self.configured();tid=self.inline_chat()
        def submit(request):
            response=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':request,
                'input':[{'type':'text','text':'usage-accounting'}]})
            self.wait(response['workflow']['id'],lambda d:d['status']=='awaiting_acceptance',20)
            return self.result('native/collaboration/session/read',{'threadId':tid})['thread']
        first=submit('models-one');usage=first['usage']
        groups={(g['engine'],g['model']):g for g in usage['byModel']}
        self.assertEqual(groups['codex','test-model']['tokenUsage']['total']['totalTokens'],70)
        self.assertEqual(groups['claude','second-model']['tokenUsage']['total']['totalTokens'],180)
        self.assertEqual(groups['deepseek','test-model']['tokenUsage']['total'],{})
        self.assertEqual(groups['deepseek','test-model']['context']['usedTokens'],123)
        self.assertEqual(usage['sessionStats']['executions'],3)
        self.assertEqual(usage['sessionStats']['turns'],1)
        self.assertEqual(usage['sessionStats']['userMessages'],1)
        self.assertTrue(usage['totalsPartial'])
        people=[dict(p) for p in first['collaborationParticipants']]
        people[1]['model']='test-model'
        self.result('native/collaboration/session/config',{'threadId':tid,'participants':people})
        second=submit('models-two');groups={(g['engine'],g['model']):g for g in second['usage']['byModel']}
        old=groups['claude','second-model'];self.assertTrue(old['historical']);self.assertEqual(old['stats']['executions'],1)
        self.assertEqual(old['tokenUsage']['total']['totalTokens'],180)
        self.assertEqual(groups['claude','test-model']['tokenUsage']['total']['totalTokens'],180)
        self.assertEqual(groups['codex','test-model']['stats']['executions'],2)
        self.assertEqual(len(groups['codex','test-model']['contexts']),2)
        self.assertEqual(second['usage']['tokenUsage']['total']['totalTokens'],500)
        self.assertEqual(second['usage']['sessionStats']['executions'],6)
        self.stop_host();self.start_host()
        restored=self.result('native/collaboration/session/read',{'threadId':tid})['thread']['usage']
        self.assertEqual(restored,second['usage'])
        repeated=self.result('native/chat/usage/read',{'threadId':tid})
        self.assertEqual(repeated,restored)

    def test_deepseek_native_usage_is_returned_for_every_collaboration_model(self):
        self.env['AI_XS_TEST_MULTIMODEL']='1';self.stop_host();self.start_host()
        self.configured();tid=self.inline_chat(('deepseek','deepseek'))
        result=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'deepseek-native-models',
            'input':[{'type':'text','text':'native-usage'}]})
        self.wait(result['workflow']['id'],lambda d:d['status']=='awaiting_acceptance',20)
        thread=self.result('native/collaboration/session/read',{'threadId':tid})['thread'];usage=thread['usage']
        groups={g['model']:g for g in usage['byModel'] if g['engine']=='deepseek'}
        for model in ('test-model','second-model'):
            self.assertEqual(groups[model]['tokenUsage']['total']['totalTokens'],15)
            self.assertEqual(groups[model]['context']['usedTokens'],13)
            self.assertEqual(groups[model]['context']['windowTokens'],1000)
            self.assertEqual(groups[model]['stats']['executions'],1)
        self.assertEqual(usage['tokenUsage']['total']['totalTokens'],30)
        self.assertEqual(usage['sessionStats']['executions'],2)
        self.assertEqual(usage['sessionStats']['userMessages'],1)

    def test_live_usage_before_completion_and_stop_keep_same_model_counters(self):
        self.configured();tid=self.inline_chat(('codex','codex'))
        response=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'live-usage',
            'input':[{'type':'text','text':'usage-accounting wait-for-interrupt'}]})
        key=response['workflow']['id']
        self.wait(key,lambda d:all(a.get('usage') for t in d['tasks'] for a in t['attempts']) and
            sum(t['status']=='running' for t in d['tasks'])==2)
        usage=self.result('native/collaboration/session/read',{'threadId':tid})['thread']['usage']
        groups={(g['engine'],g['model']):g for g in usage['byModel']}
        for model in ('test-model','second-model'):
            self.assertEqual(groups['codex',model]['tokenUsage']['total']['totalTokens'],70)
            self.assertTrue(groups['codex',model]['isActive'])
            self.assertEqual(groups['codex',model]['context']['usedTokens'],45)
        self.result('native/collaboration/cancel',{'workflowId':key})
        self.wait(key,lambda d:not any(t['status'] in ('starting','running','stopping') for t in d['tasks']))
        final=self.result('native/collaboration/session/read',{'threadId':tid})['thread']['usage']
        self.assertEqual(final['tokenUsage']['total']['totalTokens'],140)
        for group in final['byModel']:
            if group['used']:
                self.assertFalse(group['isActive']);self.assertEqual(group['stats']['statuses'],{'interrupted':1})

    def delete_finished(self, tid):
        until=time.monotonic()+5
        while time.monotonic()<until:
            reply=self.call('native/chat/thread/delete',{'threadId':tid})
            if 'error' not in reply:return reply['result']
            if '尚未' not in reply['error']:self.fail(reply['error'])
            time.sleep(.05)
        self.fail('Execution did not fully exit before deletion: '+str(reply))

    def test_delete_purges_collaboration_records_homes_logs_and_git_worktrees(self):
        self.configured();sentinel='delete-private-'+os.urandom(8).hex()
        source=self.projects/'source.txt';source.write_text('keep original source')
        for args in [('init',),('config','user.email','test@example.invalid'),('config','user.name','Test'),('add','.'),('commit','-m','initial')]:
            workspace.git(self.projects,*args)
        tid=self.inline_chat();response=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'delete-all-data',
            'input':[{'type':'text','text':sentinel}]})
        key=response['workflow']['id'];doc=self.wait(key,lambda d:d['status']=='awaiting_acceptance',20)
        attempts=[a['id'] for t in doc['tasks'] for a in t['attempts']]
        root=self.folder/'state'/'collaboration'
        self.assertTrue(all((root/'attempts'/a/'spec.json').exists() for a in attempts))
        self.assertTrue(self.delete_finished(tid)['deleted'])
        self.assertTrue(all(not (root/'attempts'/a).exists() for a in attempts))
        self.assertIn('协作不存在',self.call('native/collaboration/read',{'workflowId':key})['error'])
        self.assertEqual(source.read_text(),'keep original source')
        worktrees=workspace.git(self.projects,'worktree','list','--porcelain').decode()
        self.assertTrue(all(a not in worktrees for a in attempts))
        self.assertNotIn(sentinel,(root/'collaboration.sqlite').read_bytes().decode(errors='ignore'))
        if (root/'collaboration.sqlite-wal').exists():self.assertEqual((root/'collaboration.sqlite-wal').stat().st_size,0)
        self.stop_host();self.start_host()
        self.assertNotIn(tid,[r['id'] for r in self.result('native/threads/list',{})['data']])

    def test_delete_retains_shared_workflow_and_attachment_until_last_fork(self):
        import base64
        self.configured();tid=self.inline_chat(('codex','codex'))
        image=self.result('files/upload',{'name':'test.png','data':base64.b64encode(b'\x89PNG\r\n\x1a\nexample').decode()})['path']
        response=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'delete-fork-image',
            'input':[{'type':'text','text':'hello'},{'type':'localImage','path':image}]})
        key=response['workflow']['id'];doc=self.wait(key,lambda d:d['status']=='awaiting_acceptance',20)
        fork=self.result('native/chat/thread/fork',{'threadId':tid})['thread'];forkid=fork['id']
        self.delete_finished(tid)
        self.assertEqual(self.result('native/collaboration/read',{'workflowId':key})['workflow']['conversationId'],forkid)
        self.assertTrue(Path(image).exists())
        preserved=self.result('native/collaboration/session/read',{'threadId':forkid})['thread']
        self.assertEqual(preserved['usage']['tokenUsage']['total'],fork['usage']['tokenUsage']['total'])
        self.assertTrue(preserved['turns'])
        self.delete_finished(forkid)
        self.assertFalse(Path(image).exists())
        self.assertIn('error',self.call('native/collaboration/read',{'workflowId':key}))

    def test_delete_active_collaboration_is_rejected_without_hiding_history(self):
        self.configured();tid=self.inline_chat(('codex','codex'))
        response=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'delete-running',
            'input':[{'type':'text','text':'wait-for-interrupt'}]})
        key=response['workflow']['id'];self.wait(key,lambda d:sum(t['status']=='running' for t in d['tasks'])==2)
        failed=self.call('native/chat/thread/delete',{'threadId':tid});self.assertIn('停止',failed['error'])
        self.assertTrue(self.result('native/chat/thread/read',{'threadId':tid})['thread']['turns'])
        self.result('native/collaboration/cancel',{'workflowId':key})
        self.wait(key,lambda d:not any(t['status'] in ('starting','running','stopping') for t in d['tasks']))
        self.assertTrue(self.delete_finished(tid)['deleted'])

    def test_delete_one_shared_chat_removes_all_three_native_agent_histories(self):
        self.configured()
        tid=self.result('native/chat/thread/start',{'engine':'codex','cwd':str(self.projects),'model':'test-model'})['thread']['id']
        for engine in ('codex','claude','deepseek'):
            self.result('native/chat/switch',{'threadId':tid,'engine':engine,'model':'test-model'})
            self.result('native/chat/turn/start',{'threadId':tid,'engine':engine,'model':'test-model','input':[{'type':'text','text':'private history '+engine}]})
            until=time.monotonic()+10
            while time.monotonic()<until:
                chat=self.result('native/chat/thread/read',{'threadId':tid})['thread']
                if chat['turns'][-1]['status']=='completed':break
                time.sleep(.05)
        self.assertEqual(self.delete_finished(tid)['nativeSessionsDeleted'],3)
        state=self.folder/'state'
        self.assertFalse(list((state/'claude').glob('claude-*.json')))
        self.assertFalse(list((state/'deepseek').glob('deepseek-*.json')))
        self.assertFalse(json.loads((self.folder/'.codex'/'fake-threads.json').read_text()))
        self.assertNotIn('private history',(state/'conversations'/'index.json').read_text())
        self.stop_host();self.start_host()
        self.assertNotIn(tid,[r['id'] for r in self.result('native/threads/list',{})['data']])

    def test_editing_before_collaboration_keeps_old_round_only_in_backup_branch(self):
        self.configured();tid=self.inline_chat(('codex','codex'))
        result=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'edit-collaboration','input':[{'type':'text','text':'hello'}]})
        key=result['workflow']['id'];self.wait(key,lambda d:d['status']=='awaiting_acceptance')
        thread=self.result('native/collaboration/session/config',{'threadId':tid,'mode':'independent'})['thread']
        turn=thread['turns'][0];item=turn['items'][0]
        result=self.result('native/chat/turn/edit',{'threadId':tid,'engine':'codex','turnId':turn['id'],'itemId':item['id'],'text':'edited independent message','model':'test-model'})
        current=self.result('native/collaboration/session/read',{'threadId':tid})
        self.assertFalse(current['workflows'])
        self.assertFalse(any(t.get('workflowId')==key for t in current['thread']['turns']))
        backup=self.result('native/chat/thread/read',{'threadId':result['editBackupThreadId']})['thread']
        self.assertTrue(any(t.get('workflowId')==key for t in backup['turns']))

    def test_legacy_workflows_migrate_into_the_original_conversation_once(self):
        self.configured();doc=self.create(('codex',),'hello');key=doc['id'];tid=doc['conversationId']
        self.result('native/collaboration/start',{'workflowId':key});self.wait(key,lambda d:d['status']=='awaiting_acceptance')
        self.result('native/collaboration/accept',{'workflowId':key})
        first=self.result('native/collaboration/session/read',{'threadId':tid})['thread']
        second=self.result('native/collaboration/session/read',{'threadId':tid})['thread']
        self.assertEqual(first,second);self.assertEqual(len(first['turns']),1)
        self.assertEqual(first['turns'][0]['workflowId'],key)
        self.assertIn('legacySummary',first['turns'][0])

    def test_disabled_agent_never_silently_starts_and_maintenance_blocks(self):
        self.configured();self.result('native/agents/control',{'action':'stop','target':'claude','confirmed':True})
        d=self.create(('claude',),'hello');key=d['id'];self.result('native/collaboration/start',{'workflowId':key});time.sleep(.7)
        d=self.result('native/collaboration/read',{'workflowId':key})['workflow']
        self.assertEqual(d['tasks'][0]['status'],'queued');self.assertIn('已停止',d['tasks'][0]['waitReason'])
        self.assertFalse(self.result('native/agents/status')['data'][1]['enabled'])

    def test_ordinary_conversation_shares_slots_and_queued_cancel_is_local(self):
        self.configured();d=self.create(('codex','codex'));key=d['id'];self.result('native/collaboration/start',{'workflowId':key})
        self.wait(key,lambda d:sum(t['status']=='running' for t in d['tasks'])==2)
        other=self.folder/'other';other.mkdir()
        tid=self.result('native/chat/thread/start',{'engine':'claude','cwd':str(other)})['thread']['id']
        queued=self.result('native/chat/turn/start',{'engine':'claude','threadId':tid,'input':[{'type':'text','text':'hello'}]})
        self.assertTrue(queued['queued'])
        self.result('native/chat/turn/interrupt',{'threadId':tid,'turnId':queued['turn']['id']})
        self.assertEqual(self.result('native/chat/thread/read',{'threadId':tid})['thread']['turns'][-1]['status'],'interrupted')
        self.assertEqual(self.result('native/collaboration/read',{'workflowId':key})['workflow']['active'],2)
        queued=self.result('native/chat/turn/start',{'engine':'claude','threadId':tid,'input':[{'type':'text','text':'hello'}]})
        self.result('native/collaboration/pause',{'workflowId':key});self.result('native/collaboration/stop',{'workflowId':key,'taskId':'t0'})
        self.wait(key,lambda d:d['tasks'][0]['status']=='interrupted')
        until=time.monotonic()+10
        while time.monotonic()<until:
            chat=self.result('native/chat/thread/read',{'threadId':tid})['thread']
            if chat['turns'][-1]['status']=='completed':break
            time.sleep(.1)
        self.assertEqual(chat['turns'][-1]['status'],'completed',chat)
        self.assertFalse(any(t.get('queued') and t['status']=='inProgress' for t in chat['turns']))
        self.result('native/collaboration/cancel',{'workflowId':key})

    def test_native_approval_ids_are_namespaced_for_claude_and_deepseek_pairs(self):
        self.stop_host();self.env['AI_XS_TEST_MULTIMODEL']='1';self.start_host();self.configured()
        for engine in ('claude','deepseek'):
            with self.subTest(engine=engine):
                d=self.create((engine,engine),'permission');key=d['id']
                self.result('native/collaboration/start',{'workflowId':key})
                d=self.wait(key,lambda d:all(t['status']=='waiting_input' for t in d['tasks']))
                approvals=[t['approvals'][0] for t in d['tasks']]
                self.assertNotEqual(approvals[0]['id'],approvals[1]['id'])
                self.assertEqual([t['attempts'][0]['config']['model'] for t in d['tasks']],['test-model','second-model'])
                for task in d['tasks']:
                    a=task['approvals'][0]
                    decision={'decision':'accept'}
                    self.result('native/collaboration/respond',{'workflowId':key,'taskId':task['id'],
                        'attemptId':a['attemptId'],'id':a['id'],'result':decision})
                d=self.wait(key,lambda d:d['status'] in ('awaiting_acceptance','partial_failure'))
                self.assertEqual(d['status'],'awaiting_acceptance',d)

    def test_restart_pauses_workflow_and_does_not_replay_attempts(self):
        self.configured();d=self.create(('codex',),'wait-for-interrupt');key=d['id']
        self.result('native/collaboration/start',{'workflowId':key});self.wait(key,lambda d:d['tasks'][0]['status']=='running')
        self.stop_host();self.start_host()
        d=self.result('native/collaboration/read',{'workflowId':key})['workflow']
        self.assertEqual(d['status'],'paused');self.assertEqual(len(d['tasks'][0]['attempts']),1)
        self.assertIn(d['tasks'][0]['status'],('interrupted','needs_reconciliation'))
        time.sleep(.5);d=self.result('native/collaboration/read',{'workflowId':key})['workflow'];self.assertEqual(len(d['tasks'][0]['attempts']),1)

    def test_running_reassignment_waits_for_exit_and_keeps_attempt_history(self):
        self.configured();d=self.create(('codex',),'wait-for-interrupt');key=d['id']
        self.result('native/collaboration/start',{'workflowId':key});self.wait(key,lambda d:d['tasks'][0]['status']=='running')
        self.result('native/collaboration/reassign',{'workflowId':key,'taskId':'t0','when':'interrupt',
            'participant':{'engine':'claude','model':'test-model','permission':'ask','name':'replacement'}})
        d=self.wait(key,lambda d:len(d['tasks'][0]['attempts'])==2 and d['tasks'][0]['status']=='running')
        attempts=d['tasks'][0]['attempts'];self.assertEqual(attempts[0]['status'],'interrupted')
        self.assertEqual(attempts[1]['config']['engine'],'claude')
        self.assertGreaterEqual(attempts[1]['startedAt'],attempts[0]['finishedAt'])
        self.result('native/collaboration/cancel',{'workflowId':key})

    def test_startup_finishes_legacy_deleted_conversations(self):
        self.configured()
        tid=self.result('native/chat/thread/start',{'engine':'codex','cwd':str(self.projects),'model':'test-model'})['thread']['id']
        self.result('native/chat/turn/start',{'threadId':tid,'engine':'codex','model':'test-model',
            'input':[{'type':'text','text':'legacy hidden conversation'}]})
        until=time.monotonic()+10
        while time.monotonic()<until:
            chat=self.result('native/chat/thread/read',{'threadId':tid})['thread']
            if chat['turns'] and chat['turns'][-1]['status']=='completed':break
            time.sleep(.05)
        self.stop_host()
        indexfile=self.folder/'state'/'conversations'/'index.json'
        index=json.loads(indexfile.read_text());row=index[tid]
        row['deleted']=True;row.pop('deletionComplete',None);row.pop('deletedNativeSources',None)
        indexfile.write_text(json.dumps(index,ensure_ascii=False))
        self.start_host()
        until=time.monotonic()+8
        while time.monotonic()<until:
            if json.loads(indexfile.read_text())[tid].get('deletionComplete'):break
            time.sleep(.05)
        self.assertTrue(json.loads(indexfile.read_text())[tid].get('deletionComplete'))
        self.assertNotIn(tid,[r['id'] for r in self.result('native/threads/list',{})['data']])
