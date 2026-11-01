"""Backend delivery, verification and public API contract regressions."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
import collaboration_workspace as workspace
import collaboration_policy as policy
import collaboration_api as api
from worker_pool import process_identity
from collaboration_store import CollaborationStore
from collaboration_service import CollaborationService
from task_scheduler import TaskScheduler
import test_collaboration as existing


class DeliveryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.folder=Path(self.temp.name);self.repo=self.folder/'repo';self.repo.mkdir()
        for args in [('init',),('config','user.email','test@example.invalid'),('config','user.name','Test')]:workspace.git(self.repo,*args)
        for name in ('a','b','c'):(self.repo/(name+'.txt')).write_text('base\n')
        workspace.git(self.repo,'add','.');workspace.git(self.repo,'commit','-m','base')
    def tearDown(self):self.temp.cleanup()
    def change(self,name,file,text,patches=()):
        w=workspace.prepare(self.repo,self.folder/name,list(patches));(Path(w['path'])/file).write_text(text)
        result=self.folder/(name+'-result');result.mkdir();return workspace.capture(w,result)
    def test_sequential_disjoint_integration_is_idempotent_and_preserves_user_index(self):
        a=self.change('one','a.txt','one\n');b=self.change('two','b.txt','two\n');ledger=self.folder/'ledger.json'
        before=workspace.git(self.repo,'ls-files','--stage')
        workspace.integrate(a,ledger);workspace.integrate(b,ledger)
        self.assertEqual((self.repo/'a.txt').read_text(),'one\n');self.assertEqual((self.repo/'b.txt').read_text(),'two\n')
        self.assertEqual(before,workspace.git(self.repo,'ls-files','--stage'))
        self.assertEqual(workspace.integrate_many([a,b],ledger,True)['status'],'unchanged')
    def test_diamond_dependencies_include_shared_ancestor_once(self):
        a=self.change('base-change','a.txt','ancestor\n')
        b=self.change('left','b.txt','left\n',[a['patch']]);c=self.change('right','c.txt','right\n',[a['patch']])
        combined=workspace.prepare(self.repo,self.folder/'joined',[b['patch'],c['patch'],b['patch']])
        for name,text in [('a','ancestor'),('b','left'),('c','right')]:self.assertEqual((Path(combined['path'])/(name+'.txt')).read_text(),text+'\n')
        self.assertEqual((self.repo/'a.txt').read_text(),'base\n')
    def test_true_conflict_never_touches_original(self):
        a=self.change('left','a.txt','left\n');b=self.change('right','a.txt','right\n')
        before=workspace.git(self.repo,'status','--porcelain')
        with self.assertRaises(ValueError):workspace.integrate_many([a,b],self.folder/'ledger.json',True)
        self.assertEqual((self.repo/'a.txt').read_text(),'base\n');self.assertEqual(before,workspace.git(self.repo,'status','--porcelain'))
    def test_user_edits_after_managed_integration_block_further_application(self):
        a=self.change('one','a.txt','one\n');b=self.change('two','b.txt','two\n');ledger=self.folder/'ledger.json'
        workspace.integrate(a,ledger);(self.repo/'c.txt').write_text('user work\n')
        with self.assertRaisesRegex(ValueError,'用户新增改动'):workspace.integrate(b,ledger)
        self.assertEqual((self.repo/'c.txt').read_text(),'user work\n');self.assertEqual((self.repo/'b.txt').read_text(),'base\n')
    def test_apply_receipt_recovers_crash_after_files_were_applied(self):
        a=self.change('one','a.txt','one\n');ledger=self.folder/'ledger.json';real=workspace.private_write;calls=[]
        def fail_receipt(path,value):
            calls.append(value)
            if len(calls)==2:raise OSError('simulated power loss')
            real(path,value)
        with patch.object(workspace,'private_write',side_effect=fail_receipt):
            with self.assertRaises(OSError):workspace.integrate_many([a],ledger,True)
        self.assertEqual(workspace.integrate_many([a],ledger,True)['status'],'unchanged')
    def test_snapshot_has_durable_export_before_workspace_cleanup(self):
        source=self.folder/'plain';source.mkdir();(source/'doc.txt').write_text('document')
        w=workspace.prepare(source,self.folder/'snapshot',[]);result=self.folder/'result';result.mkdir()
        artifact=workspace.capture(w,result);archive=Path(artifact['archive'])
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(),artifact['archiveSha256'])
        workspace.cleanup(w);self.assertTrue(archive.is_file())
    def test_snapshot_dependencies_merge_changes_and_retry_restores_deleted_files(self):
        source=self.folder/'plain';source.mkdir();(source/'a').write_text('base');(source/'b').write_text('base')
        artifacts=[]
        for index,file in enumerate(('a','b')):
            folder=self.folder/('attempt'+str(index));folder.mkdir();w=workspace.prepare(source,folder/'workspace',[])
            (Path(w['path'])/file).write_text(file);artifacts.append(workspace.capture(w,folder))
        joined=self.folder/'join';joined.mkdir();w=workspace.prepare(source,joined/'workspace',[]);workspace.merge_snapshots(w,artifacts)
        self.assertEqual((Path(w['path'])/'a').read_text(),'a');self.assertEqual((Path(w['path'])/'b').read_text(),'b')
        (Path(w['path'])/'b').chmod(0o755)
        (Path(w['path'])/'a').unlink();artifact=workspace.capture(w,joined)
        workspace.restore_snapshot(artifact,w['path']);self.assertFalse((Path(w['path'])/'a').exists())
        self.assertEqual((Path(w['path'])/'b').stat().st_mode&0o777,0o755)
    def test_snapshot_conflicting_files_or_changed_baselines_are_rejected(self):
        source=self.folder/'plain';source.mkdir();(source/'a').write_text('base');artifacts=[]
        for index in range(2):
            folder=self.folder/str(index);folder.mkdir();w=workspace.prepare(source,folder/'workspace',[])
            (Path(w['path'])/'a').write_text(str(index));artifacts.append(workspace.capture(w,folder))
        joined=self.folder/'join';joined.mkdir();w=workspace.prepare(source,joined/'workspace',[])
        with self.assertRaisesRegex(ValueError,'冲突'):workspace.merge_snapshots(w,artifacts)
        (source/'a').write_text('user edit');fresh=self.folder/'fresh';fresh.mkdir();w=workspace.prepare(source,fresh/'workspace',[])
        with self.assertRaisesRegex(ValueError,'基线'):workspace.merge_snapshots(w,artifacts[:1])


class PolicyTests(unittest.TestCase):
    def test_recovery_stop_checks_identity_and_requires_separate_reconciliation(self):
        with tempfile.TemporaryDirectory() as temp:
            store=CollaborationStore(temp);child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],start_new_session=True)
            try:
                doc=store.create({'status':'paused','tasks':[{'id':'t','status':'needs_reconciliation','attempts':[{'id':'a','process':{'pid':child.pid,'identity':'incorrect'}}]}]},'stop-old')
                s=SimpleNamespace(store=store,workers=SimpleNamespace(live={}),transcripts={},changed=lambda key:None,view=lambda doc:doc)
                args={'workflowId':doc['id'],'taskId':'t'}
                with self.assertRaises(ValueError):api.handle(s,'recovery/stop',args)
                api.handle(s,'recovery/stop',dict(args,confirmed=True));self.assertIsNone(child.poll())
                store.update(doc['id'],lambda d:d['tasks'][0]['attempts'][0]['process'].update(identity=process_identity(child.pid)))
                result=api.handle(s,'recovery/stop',dict(args,confirmed=True));child.wait(timeout=5)
                self.assertEqual(result['workflow']['tasks'][0]['status'],'needs_reconciliation')
            finally:
                if child.poll() is None:child.kill();child.wait(timeout=5)
                store.close()
    def test_output_evidence_reflects_files_after_commands(self):
        with tempfile.TemporaryDirectory() as temp:
            report=policy.verify({'validation':{'outputs':['generated.txt'],'checks':[[sys.executable,'-c','from pathlib import Path;Path("generated.txt").write_text("done")']]}},temp,'',[])
            self.assertEqual(report['status'],'passed')
            self.assertEqual(report['checks'][-1]['sha256'],hashlib.sha256(b'done').hexdigest())
    def test_rework_invalidates_downstream_and_stops_at_round_limit(self):
        with tempfile.TemporaryDirectory() as temp:
            s=CollaborationService.__new__(CollaborationService);s.store=CollaborationStore(temp);s.changed=lambda key:None
            task={'id':'work','status':'succeeded','dependencies':[],'attempts':[{'id':'a'}],'maxAttempts':3,'verification':{'status':'passed'}}
            review={'id':'review','kind':'review','status':'failed','dependencies':['work'],'reviewTargets':['work'],'attempts':[{'id':'b'}],'maxAttempts':3,
                    'verification':{'status':'failed','review':{'approved':False,'findings':['test missing']}}}
            summary={'id':'summary','status':'succeeded','dependencies':['work','review'],'attempts':[{'id':'c'}],'verification':{'status':'passed'}}
            try:
                doc=s.store.create({'status':'running','tasks':[task,review,summary],'rework':{'auto':True,'maxRounds':1},'accepted':False},'rework')
                s._status(doc['id']);doc=s.store.read(doc['id']);self.assertEqual(doc['reworkRounds'],1)
                self.assertFalse(doc['tasks'][0]['stale']);self.assertTrue(doc['tasks'][2]['stale']);self.assertNotIn('verification',doc['tasks'][2])
                self.assertIn('test missing',doc['messages'][0]['text'])
                s.store.update(doc['id'],lambda d:d.update(tasks=[task,review,summary]))
                s._status(doc['id']);doc=s.store.read(doc['id']);self.assertEqual(doc['status'],'partial_failure');self.assertEqual(doc['reworkRounds'],1)
            finally:s.store.close()
    def test_recovery_recaptures_snapshot_and_preserves_its_baseline(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp);source=folder/'project';source.mkdir();(source/'a').write_text('base')
            s=CollaborationService.__new__(CollaborationService);s.folder=folder/'state';s.store=CollaborationStore(s.folder)
            s.scheduler=TaskScheduler(s.store.settings,lambda:{'count':0},lambda e:True)
            attemptdir=s.folder/'attempts/attempt';attemptdir.mkdir(parents=True)
            w=workspace.prepare(source,attemptdir/'workspace',[]);(Path(w['path'])/'a').write_text('interrupted progress')
            try:
                doc=s.store.create({'cwd':str(source),'status':'running','tasks':[{'id':'t','status':'running','attempts':[{'id':'attempt','status':'running'}]}]},'recovery')
                s._recover();doc=s.store.read(doc['id']);task=doc['tasks'][0]
                self.assertEqual(task['status'],'interrupted');self.assertEqual(doc['status'],'paused')
                self.assertEqual(task['artifact']['workspace']['snapshotBase'],w['snapshotBase'])
                restored=folder/'restored';restored.mkdir();workspace.restore_snapshot(task['artifact'],restored)
                self.assertEqual((restored/'a').read_text(),'interrupted progress')
            finally:s.store.close()
    def test_verification_distinguishes_evidence_manual_and_failed_checks(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'result.txt').write_text('done')
            task={'validation':{'outputs':['result.txt'],'checks':[[sys.executable,'-c','print("checked")']]}}
            report=policy.verify(task,root,'done',[]);self.assertEqual(report['status'],'passed')
            self.assertEqual(policy.verify({},root,'I succeeded',[])['status'],'manual_required')
            self.assertEqual(policy.verify({'validation':{'outputs':['missing']}},root,'done',[])['status'],'failed')
            self.assertEqual(policy.verify({'validation':{'checks':[[sys.executable,'-c','raise SystemExit(17)']]}},root,'done',[])['checks'][0]['exitCode'],17)
    def test_review_requires_structured_decision_and_records_exact_dependencies(self):
        with tempfile.TemporaryDirectory() as temp:
            task={'kind':'review'}
            self.assertEqual(policy.verify(task,temp,'Looks good',[])['status'],'failed')
            self.assertEqual(policy.verify(task,temp,'{"review":{"approved":true,"findings":[]}}',[])['status'],'passed')
            self.assertEqual(policy.verify(task,temp,'{"review":{"approved":false,"findings":["missing tests"]}}',[])['status'],'failed')
    def test_validation_path_and_budget_inputs_are_bounded(self):
        for path in ('../outside','/absolute'):
            with self.assertRaises(ValueError):policy.validation({'outputs':[path]})
        with self.assertRaises(ValueError):policy.budget({'maxAttempts':True})
        doc={'createdAt':1,'tasks':[],'budget':{'maxAttempts':2}}
        self.assertTrue(policy.metrics(doc)['canDispatch']);self.assertEqual(policy.metrics(doc)['elapsedSeconds'],0)
        doc['tasks']=[{'attempts':[{'id':'a','startedAt':time.time(),'usage':{'tokenUsage':{'total':{'totalTokens':8}}}}]}]
        doc['budget']['maxReportedTokens']=8
        self.assertEqual(policy.metrics(doc)['stopReason'],'reported_tokens')
    def test_finished_budget_snapshot_does_not_change_after_restart(self):
        doc={'startedAt':100,'status':'awaiting_acceptance','tasks':[{'status':'succeeded','attempts':[{'startedAt':100,'finishedAt':105}]}]}
        with patch.object(policy.time,'time',return_value=1000):first=policy.metrics(doc)
        with patch.object(policy.time,'time',return_value=2000):second=policy.metrics(doc)
        self.assertEqual(first,second);self.assertEqual(first['elapsedSeconds'],5)
    def test_check_timeout_is_bounded_and_records_timeout_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            began=time.monotonic();report=policy.verify({'validation':{'timeoutSeconds':1,'checks':[[sys.executable,'-c','import time; time.sleep(30)']]}},temp,'',[])
            self.assertEqual(report['status'],'failed');self.assertTrue(report['checks'][0]['timedOut']);self.assertLess(time.monotonic()-began,5)
    def test_same_source_backoff_does_not_block_other_gateways(self):
        scheduler=TaskScheduler(lambda:{'parallel':3},lambda:{'count':0,'projects':[]},lambda e:True)
        scheduler.resources({'available':4*1024**3});scheduler.backoff('one',time.time()+20)
        self.assertIn('退避',scheduler.reserve('a','/a','codex',group='one'))
        self.assertEqual(scheduler.reserve('b','/b','claude',group='two'),'')


class BackendApiTests(unittest.TestCase):
    setUp=existing.CollaborationHostTests.setUp
    tearDown=existing.CollaborationHostTests.tearDown
    setUpClass=existing.CollaborationHostTests.setUpClass
    start_host=existing.CollaborationHostTests.start_host
    stop_host=existing.CollaborationHostTests.stop_host
    call=existing.CollaborationHostTests.call
    result=existing.CollaborationHostTests.result
    configured=existing.CollaborationHostTests.configured
    create=existing.CollaborationHostTests.create
    wait=existing.CollaborationHostTests.wait
    inline_chat=existing.CollaborationHostTests.inline_chat

    def test_task_config_rejects_cycle_and_active_task_changes(self):
        self.configured();doc=self.create(('codex','codex'),'wait-for-interrupt');key=doc['id']
        self.result('native/collaboration/task/config',{'workflowId':key,'taskId':'t0','config':{'dependencies':['t1']}})
        response=self.call('native/collaboration/task/config',{'workflowId':key,'taskId':'t1','config':{'dependencies':['t0']}})
        self.assertIn('循环',response['error'])
        self.result('native/collaboration/start',{'workflowId':key});self.wait(key,lambda d:d['tasks'][1]['status']=='running')
        self.assertIn('error',self.call('native/collaboration/task/config',{'workflowId':key,'taskId':'t1','config':{'title':'changed'}}))
        self.result('native/collaboration/cancel',{'workflowId':key})

    def test_runtime_budget_stops_deepseek_blocking_turn_and_releases_resources(self):
        self.configured();doc=self.create(('deepseek',),'wait-for-interrupt');key=doc['id']
        self.result('native/collaboration/policy',{'workflowId':key,'budget':{'maxRuntimeSeconds':2}})
        self.result('native/collaboration/start',{'workflowId':key})
        doc=self.wait(key,lambda d:d['status']=='paused' and d['tasks'][0]['status'] not in ('starting','running','stopping'))
        self.assertIn('budget:runtime',doc['pauseReasons']);self.assertEqual(len(doc['tasks'][0]['attempts']),1)
        self.assertIn(doc['tasks'][0]['status'],('interrupted','failed'))
        self.assertEqual(self.result('native/collaboration/read',{'workflowId':key})['resources']['running'],0)

    def test_stop_interrupts_post_execution_verification_command(self):
        self.configured();doc=self.create(('codex',),'hello');key=doc['id'];marker=self.folder/'verification-started'
        script='from pathlib import Path;import time;Path('+repr(str(marker))+').touch();time.sleep(30)'
        self.result('native/collaboration/task/config',{'workflowId':key,'taskId':'t0','config':{'validation':{'checks':[[sys.executable,'-c',script]],'timeoutSeconds':60}}})
        self.result('native/collaboration/start',{'workflowId':key});deadline=time.monotonic()+8
        while not marker.exists() and time.monotonic()<deadline:time.sleep(.05)
        self.assertTrue(marker.exists());started=time.monotonic()
        self.result('native/collaboration/stop',{'workflowId':key,'taskId':'t0'})
        doc=self.wait(key,lambda d:d['tasks'][0]['status']=='interrupted')
        self.assertLess(time.monotonic()-started,5);self.assertTrue(doc['tasks'][0]['verification']['cancelled'])

    def test_retry_requeues_failed_downstream_and_invalidates_its_evidence(self):
        self.configured();tid=self.inline_chat(('codex','codex'))
        response=self.result('native/collaboration/session/submit',{'threadId':tid,'template':'development','clientRequestId':'retry-review','input':[{'type':'text','text':'hello'}]})
        key=response['workflow']['id'];doc=self.wait(key,lambda d:d['status']=='partial_failure')
        self.result('native/collaboration/retry',{'workflowId':key,'taskId':doc['tasks'][0]['id']})
        doc=self.result('native/collaboration/read',{'workflowId':key})['workflow']
        self.assertEqual(doc['tasks'][1]['status'],'blocked');self.assertTrue(doc['tasks'][1]['stale']);self.assertEqual(doc['tasks'][1]['verificationStatus'],'not_run')

    def test_capabilities_history_requirements_handoff_and_cleanup_contract(self):
        self.configured();tid=self.inline_chat(('codex','codex'))
        cap=self.result('native/collaboration/capabilities');self.assertIn('batch-integration',cap['features'])
        response=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'backend-contract','input':[{'type':'text','text':'hello'}]})
        key=response['workflow']['id'];doc=self.wait(key,lambda d:d['status']=='awaiting_acceptance')
        task=doc['tasks'][0]
        self.assertEqual(task['verificationStatus'],'manual_required');self.assertEqual(doc['delivery']['status'],'awaiting_acceptance')
        page=self.result('native/collaboration/conversation',{'workflowId':key,'limit':1})
        self.assertTrue(page['hasMore']);self.assertEqual(len(page['entries']),1)
        older=self.result('native/collaboration/conversation',{'workflowId':key,'limit':1,'before':page['before']})
        self.assertNotEqual(older['entries'][0]['id'],page['entries'][0]['id'])
        request={'workflowId':key,'taskId':task['id'],'clientRequestId':'requirement-one','text':'preserve existing data'}
        first=self.result('native/collaboration/message',request);second=self.result('native/collaboration/message',request)
        self.assertEqual(first['message']['id'],second['message']['id'])
        handoff=self.result('native/collaboration/task/handoff',{'workflowId':key,'taskId':task['id']})['handoff']
        self.assertEqual(handoff['requirements'][0]['text'],'preserve existing data')
        self.assertIn('error',self.call('native/collaboration/cleanup/preview',{'workflowId':key}))
        accepted=self.result('native/collaboration/accept',{'workflowId':key})['workflow'];self.assertEqual(accepted['delivery']['status'],'accepted')
        preview=self.result('native/collaboration/cleanup/preview',{'workflowId':key})
        cleaned=self.result('native/collaboration/cleanup',{'workflowId':key,'previewToken':preview['previewToken'],'confirmed':True})
        self.assertTrue(cleaned['cleanedAttemptIds'])
        restored=self.result('native/collaboration/conversation',{'workflowId':key});self.assertTrue(restored['entries'])
    def test_development_template_orders_review_and_never_fakes_approval(self):
        self.configured();tid=self.inline_chat(('codex','codex'))
        response=self.result('native/collaboration/session/submit',{'threadId':tid,'template':'development','clientRequestId':'staged-contract','input':[{'type':'text','text':'hello'}]})
        doc=self.wait(response['workflow']['id'],lambda d:d['status']=='partial_failure')
        work,review=doc['tasks'];self.assertEqual(review['kind'],'review');self.assertEqual(review['dependencies'],[work['id']])
        self.assertGreaterEqual(review['attempts'][0]['startedAt'],work['attempts'][0]['finishedAt'])
        self.assertEqual(review['verificationStatus'],'failed')
        self.assertIn('error',self.call('native/collaboration/accept',{'workflowId':doc['id']}))
    def test_attempt_budget_pauses_dispatch_and_can_be_raised(self):
        self.configured();doc=self.create(('codex','codex'),'hello');key=doc['id']
        self.result('native/collaboration/policy',{'workflowId':key,'budget':{'maxAttempts':1}})
        self.result('native/collaboration/start',{'workflowId':key})
        paused=self.wait(key,lambda d:d['status']=='paused')
        self.assertIn('budget:attempts',paused['pauseReasons']);self.assertEqual(sum(len(t['attempts']) for t in paused['tasks']),1)
        self.result('native/collaboration/policy',{'workflowId':key,'budget':{'maxAttempts':4}})
        self.result('native/collaboration/resume',{'workflowId':key});self.wait(key,lambda d:d['status']=='awaiting_acceptance')
    def test_validation_configuration_cannot_fake_success_and_recovery_inspection_is_read_only(self):
        self.configured();doc=self.create(('codex',),'hello');key=doc['id']
        self.result('native/collaboration/task/config',{'workflowId':key,'taskId':'t0','config':{'validation':{'outputs':['missing-result.txt']}}})
        self.result('native/collaboration/start',{'workflowId':key});doc=self.wait(key,lambda d:d['status']=='partial_failure')
        self.assertEqual(doc['tasks'][0]['status'],'failed');self.assertEqual(doc['delivery']['status'],'verification_failed')
        state=self.result('native/collaboration/recovery/read',{'workflowId':key,'taskId':'t0'})
        self.assertFalse(state['tasks'][0]['canReconcile'])
        self.assertIn('error',self.call('native/collaboration/reconcile',{'workflowId':key,'taskId':'t0','confirmed':True}))
    def test_batch_preview_requires_current_token_and_keeps_original_index(self):
        self.configured()
        for args in [('init',),('config','user.email','test@example.invalid'),('config','user.name','Test')]:workspace.git(self.projects,*args)
        (self.projects/'a.txt').write_text('base');workspace.git(self.projects,'add','.');workspace.git(self.projects,'commit','-m','base')
        doc=self.create(('codex','codex'),'hello');key=doc['id'];self.result('native/collaboration/start',{'workflowId':key})
        self.wait(key,lambda d:d['status']=='awaiting_acceptance')
        args={'workflowId':key,'taskIds':['t0','t1']};preview=self.result('native/collaboration/integration/preview',args)
        self.assertTrue(preview['canApply'])
        self.assertIn('error',self.call('native/collaboration/integration/apply',dict(args,previewToken='stale')))
        result=self.result('native/collaboration/integration/apply',dict(args,previewToken=preview['previewToken']))
        self.assertEqual(result['status'],'unchanged');self.assertFalse(workspace.git(self.projects,'status','--porcelain'))
    def test_restart_requirement_waits_for_old_attempt_exit_and_creates_new_attempt(self):
        self.configured();tid=self.inline_chat(('codex','codex'))
        response=self.result('native/collaboration/session/submit',{'threadId':tid,'clientRequestId':'restart-contract','input':[{'type':'text','text':'wait-for-interrupt'}]})
        key=response['workflow']['id'];doc=self.wait(key,lambda d:all(t['status']=='running' for t in d['tasks']))
        task=doc['tasks'][0];old=task['attempts'][0]['id']
        self.result('native/collaboration/message',{'workflowId':key,'taskId':task['id'],'clientRequestId':'restart-msg','text':'additional requirement',
            'delivery':'restart','confirmed':True})
        doc=self.wait(key,lambda d:len(d['tasks'][0]['attempts'])==2)
        attempts=doc['tasks'][0]['attempts'];self.assertNotEqual(old,attempts[-1]['id'])
        self.assertGreaterEqual(attempts[-1]['startedAt'],attempts[0]['finishedAt'])
        root=self.folder/'state/collaboration/attempts'/attempts[-1]['id']
        until=time.monotonic()+5
        while not (root/'handoff.json').exists() and time.monotonic()<until:time.sleep(.05)
        self.assertIn('additional requirement',(root/'handoff.json').read_text())
        self.assertEqual(json.loads((root/'handoff.json').read_text())['previousAttemptId'],old)
        self.result('native/collaboration/cancel',{'workflowId':key})
        self.wait(key,lambda d:not any(t['status'] in ('starting','running','stopping') for t in d['tasks']))
