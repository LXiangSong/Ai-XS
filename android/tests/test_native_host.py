"""Exercise the packaged native service through real private pipes, with fake Agents."""
import base64
import json
import os
from pathlib import Path
import queue
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[2]


class NativeHostTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        subprocess.run([sys.executable,str(ROOT/'android/tools/prepare_app_assets.py')],check=True,capture_output=True)

    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.folder=Path(self.tmp.name)
        self.service=self.folder/'service';self.service.mkdir()
        with zipfile.ZipFile(ROOT/'android/app/src/main/assets/runtime/native.zip') as archive:archive.extractall(self.service)
        self.projects=self.folder/'projects';self.projects.mkdir()
        binpath=self.folder/'bin';binpath.mkdir()
        codex=binpath/'codex';codex.write_text('#!'+sys.executable+'\n'+(ROOT/'tests/fake_codex.py').read_text());codex.chmod(0o700)
        self.env={**os.environ,'HOME':str(self.folder),'AI_XS_STATE_DIR':str(self.folder/'state'),
            'PATH':str(binpath)+os.pathsep+os.environ['PATH'],'AI_XS_TEST_WIRE_LOG':str(self.folder/'wire.jsonl'),
            'CODEX_HOME':str(self.folder/'.codex'),
            'AI_XS_ENGINE_HOME':str(self.folder),'AI_XS_SINGLE_AGENT':'1','CODEX_ZH_PROJECTS':str(self.projects),
            'AI_XS_CLAUDE_COMMAND':json.dumps([sys.executable,str(ROOT/'tests/fake_claude.py')]),
            'AI_XS_DEEPSEEK_COMMAND':json.dumps([sys.executable,str(ROOT/'tests/fake_deepseek.py')])}
        self.start_host()

    def start_host(self):
        self.process=subprocess.Popen([sys.executable,'-u',str(self.service/'native_host.py')],cwd=self.folder,env=self.env,
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        self.messages=queue.Queue();self.events=[];self.next_id=0
        def read():
            for line in self.process.stdout:
                try:self.messages.put(json.loads(line))
                except ValueError:self.messages.put({'invalid-output':line})
        self.reader=threading.Thread(target=read,daemon=True);self.reader.start()
        ready=self.messages.get(timeout=10);self.assertTrue(ready['ready']);self.assertEqual(ready['transport'],'private-stdio')

    def tearDown(self):
        self.stop_host();self.tmp.cleanup()

    def stop_host(self):
        self.process.stdin.close()
        try:self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:self.process.kill();self.process.wait(timeout=5)
        self.reader.join(timeout=2);self.process.stdout.close();self.process.stderr.close()

    def call(self,method,params=None):
        self.next_id+=1;ident=self.next_id
        self.process.stdin.write(json.dumps({'id':ident,'method':method,'params':params or {}})+'\n');self.process.stdin.flush()
        deadline=time.monotonic()+10
        while True:
            message=self.messages.get(timeout=max(.1,deadline-time.monotonic()))
            if 'event' in message:self.events.append(message['event']);continue
            self.assertEqual(message.get('id'),ident,message)
            return message

    def result(self,method,params=None):
        message=self.call(method,params);self.assertNotIn('error',message,message);return message['result']

    def test_edit_roundtrip_keeps_original_branch_for_each_adapter(self):
        for engine in ('codex','claude','deepseek'):
            with self.subTest(engine=engine):
                self.result('provider/save',{'engine':engine,'mode':'thirdparty','name':'Protocol test',
                    'baseUrl':'https://gateway.invalid/v1','protocol':{'codex':'responses','claude':'anthropic-messages','deepseek':'openai-completions'}[engine],
                    'models':['test-model'],'key':'private-edit-test'})
                tid=self.result('native/chat/thread/start',{'engine':engine,'cwd':str(self.projects),'model':'test-model'})['thread']['id']
                doc=self.shared_turn(tid,engine,'original prompt')
                old=doc['turns'][0];item=next(i for i in old['items'] if i['type']=='userMessage')
                result=self.result('native/chat/turn/edit',{'threadId':tid,'engine':engine,'turnId':old['id'],
                    'itemId':item['id'],'text':'edited prompt','model':'test-model','effort':'low'})
                self.assertEqual(result['thread']['id'],tid)
                backup=self.result('native/chat/thread/read',{'threadId':result['editBackupThreadId']})['thread']
                self.assertEqual(backup['turns'],doc['turns'])
                deadline=time.monotonic()+5
                while time.monotonic()<deadline:
                    current=self.result('native/chat/thread/read',{'threadId':tid})['thread']
                    if current['turns'][-1]['status']!='inProgress':break
                    time.sleep(.02)
                self.assertEqual(current['turns'][-1]['status'],'completed')
                content=next(i['content'] for i in current['turns'][0]['items'] if i['type']=='userMessage')
                self.assertEqual(content,[{'type':'text','text':'edited prompt'}])
                self.assertEqual(len(current['turns']),1)

    def test_interrupt_and_terminal_status_roundtrip_for_all_three_agents(self):
        for engine in ('codex','claude','deepseek'):
            self.result('provider/save',{'engine':engine,'mode':'thirdparty','name':'Local stop test',
                'baseUrl':'https://gateway.invalid/v1','protocol':{'codex':'responses','claude':'anthropic-messages','deepseek':'openai-completions'}[engine],
                'models':['test-model'],'key':'private-stop-test'})
            tid=self.result('native/chat/thread/start',{'engine':engine,'cwd':str(self.projects),'model':'test-model'})['thread']['id']
            turn=self.result('native/chat/turn/start',{'threadId':tid,'engine':engine,'model':'test-model',
                'input':[{'type':'text','text':'wait-for-interrupt'}]})['turn']
            time.sleep(.1)
            self.result('native/chat/turn/interrupt',{'threadId':tid,'engine':engine,'turnId':turn['id']})
            deadline=time.monotonic()+4
            while time.monotonic()<deadline:
                doc=self.result('native/chat/turn/status',{'threadId':tid,'engine':engine})['thread']
                if doc['turns'][-1]['status']!='inProgress':break
                time.sleep(.05)
            self.assertEqual(doc['turns'][-1]['status'],'interrupted',engine)
            self.assertNotIn('error',doc['turns'][-1],engine)

    def test_extension_install_is_available_to_all_agents_and_assignment_changes_next_turn(self):
        source=self.folder/'demo-skill';source.mkdir()
        (source/'SKILL.md').write_text('---\nname: xs-regression-skill\ndescription: Verify installed resources\n---\nRead local resources when needed.')
        preview=self.result('native/extensions/import',{'path':str(source)})
        installed=self.result('native/extensions/install',{'previewId':preview['previewId'],
            'candidateId':preview['data'][0]['id'],'agents':['all']})['extension']
        self.assertEqual(self.result('native/extensions/list')['data'][0]['id'],installed['id'])
        threads={}
        for engine in ('codex','claude','deepseek'):
            self.result('provider/save',{'engine':engine,'mode':'thirdparty','name':'Extension test',
                'baseUrl':'https://gateway.invalid/v1','protocol':{'codex':'responses','claude':'anthropic-messages','deepseek':'openai-completions'}[engine],
                'models':['test-model'],'key':'private-test-key'})
            tid=self.result('native/chat/thread/start',{'engine':engine,'cwd':str(self.projects),'model':'test-model'})['thread']['id']
            threads[engine]=tid
            self.shared_turn(tid,engine,'demo')
        wire=[json.loads(line) for line in (self.folder/'wire.jsonl').read_text().splitlines()]
        for engine in ('codex','claude','deepseek'):
            self.assertTrue(any(row['engine']==engine and 'xs-regression-skill' in json.dumps(row) for row in wire),engine)
        self.result('native/extensions/configure',{'id':installed['id'],'agents':['deepseek']})
        start=len(wire)
        self.shared_turn(threads['codex'],'codex','demo again')
        wire=[json.loads(line) for line in (self.folder/'wire.jsonl').read_text().splitlines()][start:]
        self.assertFalse(any(row['engine']=='codex' and 'xs-regression-skill' in json.dumps(row) for row in wire))
        self.result('native/extensions/remove',{'id':installed['id']})
        self.assertEqual(self.result('native/extensions/list')['data'],[])

    def test_stop_control_bypasses_four_busy_request_workers(self):
        engine='deepseek'
        self.result('provider/save',{'engine':engine,'mode':'thirdparty','name':'Local','baseUrl':'https://gateway.invalid/v1',
            'protocol':'openai-completions','models':['test-model'],'key':'private-stop-test'})
        tid=self.result('native/chat/thread/start',{'engine':engine,'cwd':str(self.projects),'model':'test-model'})['thread']['id']
        turn=self.result('native/chat/turn/start',{'threadId':tid,'engine':engine,'input':[{'type':'text','text':'wait'}]})['turn']
        time.sleep(.1)
        for i in range(4):
            marker=self.folder/('busy-'+str(i))
            packet={'id':'busy-'+str(i),'method':'command/exec','params':{'command':[sys.executable,'-c',
                'from pathlib import Path; import time; Path('+repr(str(marker))+').touch(); time.sleep(2.5)']}}
            self.process.stdin.write(json.dumps(packet)+'\n');self.process.stdin.flush()
        deadline=time.monotonic()+3
        while not all((self.folder/('busy-'+str(i))).exists() for i in range(4)):
            self.assertLess(time.monotonic(),deadline);time.sleep(.02)
        started=time.monotonic()
        self.result('native/chat/turn/interrupt',{'threadId':tid,'engine':engine,'turnId':turn['id']})
        self.assertLess(time.monotonic()-started,1,'Stop must not queue behind slow operations')

    def test_projects_files_and_errors_roundtrip_without_http(self):
        project=self.result('projects/create',{'name':'native-project'})['project']
        path=str(Path(project['path'])/'sample.txt')
        self.result('native/createFile',{'path':path})
        text='原生文件编辑验证'
        self.result('fs/writeFile',{'path':path,'dataBase64':base64.b64encode(text.encode()).decode()})
        value=self.result('fs/readFile',{'path':path})
        self.assertEqual(base64.b64decode(value['dataBase64']).decode(),text)
        self.assertIn('error',self.call('native/createFile',{'path':path}))
        self.assertEqual(Path(path).read_text(),text)
        self.assertEqual(self.result('native/status')['transport'],'private-stdio')
        self.assertIn('error',self.call('not-a-method'))
        self.assertTrue(self.result('model/list',{'engine':'deepseek'})['data'])

    def test_agent_update_maintenance_rejects_active_tasks_and_blocks_new_starts(self):
        for engine in ('codex','claude','deepseek'):
            self.result('provider/save',{'engine':engine,'mode':'thirdparty','name':'Maintenance test',
                'baseUrl':'https://gateway.invalid/v1','protocol':{'codex':'responses','claude':'anthropic-messages','deepseek':'openai-completions'}[engine],
                'models':['test-model'],'key':'private-test'})
            tid=self.result('native/chat/thread/start',{'engine':engine,'cwd':str(self.projects),'model':'test-model'})['thread']['id']
            turn=self.result('native/chat/turn/start',{'threadId':tid,'engine':engine,'input':[{'type':'text','text':'wait-for-interrupt'}]})['turn']
            denied=self.call('native/maintenance/prepare')
            self.assertIn('error',denied)
            self.assertIn('任务在运行',denied['error'])
            self.result('native/chat/turn/interrupt',{'threadId':tid,'engine':engine,'turnId':turn['id']})
            deadline=time.monotonic()+4
            while time.monotonic()<deadline:
                doc=self.result('native/chat/thread/read',{'threadId':tid})['thread']
                if doc['turns'][-1]['status']!='inProgress':break
                time.sleep(.03)
            self.assertEqual(doc['turns'][-1]['status'],'interrupted')
        self.assertEqual(self.result('native/maintenance/prepare'),{'ready':True})
        for method in ('native/chat/turn/start','native/chat/turn/edit','turn/start'):
            denied=self.call(method,{'threadId':tid,'engine':'deepseek','input':[{'type':'text','text':'new task'}]})
            self.assertIn('正在更新 Agent',denied['error'])
        self.assertEqual(self.result('native/chat/thread/read',{'threadId':tid})['thread']['id'],tid)

    def shared_turn(self,tid,engine,prompt):
        self.events.clear()
        self.result('native/chat/turn/start',{'threadId':tid,'engine':engine,'model':'test-model',
            'effort':'max' if engine=='codex' else 'high','input':[{'type':'text','text':prompt}]})
        deadline=time.monotonic()+7
        while time.monotonic()<deadline:
            doc=self.result('native/chat/thread/read',{'threadId':tid})['thread']
            if doc['turns'] and doc['turns'][-1]['status']!='inProgress':return doc
            time.sleep(.02)
        self.fail('Common turn did not finish')

    def test_same_conversation_handoff_reaches_all_three_real_adapters_and_survives_restart(self):
        for engine in ('codex','claude','deepseek'):
            self.result('provider/save',{'engine':engine,'mode':'thirdparty','name':'Protocol test',
                'baseUrl':'https://gateway.invalid/v1','protocol':{'codex':'responses','claude':'anthropic-messages','deepseek':'openai-completions'}[engine],
                'models':['test-model'],'key':'private-handoff-test'})
        self.result('state/write',{'settings':{'instructions':'global instruction sentinel'}})
        tid=self.result('native/chat/thread/start',{'engine':'deepseek','cwd':str(self.projects),'model':'test-model'})['thread']['id']
        self.shared_turn(tid,'deepseek','Remember violet-731 for the next Agent')
        for engine in ('codex','claude','deepseek'):
            before=(self.folder/'wire.jsonl').read_text()
            switched=self.result('native/chat/switch',{'threadId':tid,'engine':engine,'model':'test-model'})['thread']
            self.assertEqual(switched['id'],tid)
            self.assertEqual((self.folder/'wire.jsonl').read_text(),before,'Switching must not launch any CLI')
            doc=self.shared_turn(tid,engine,'Continue the previous task; '+engine+'-input-sentinel')
            self.assertEqual(doc['id'],tid)
        self.assertEqual([t['engine'] for t in doc['turns']],['deepseek','codex','claude','deepseek'])
        wire=[json.loads(line) for line in (self.folder/'wire.jsonl').read_text().splitlines()]
        codex=next(r['message']['params'] for r in wire if r['engine']=='codex' and r['message'].get('method')=='thread/start')
        self.assertIn('violet-731',codex['developerInstructions']);self.assertIn('DeepSeek 测试回复',codex['developerInstructions'])
        self.assertIn('global instruction sentinel',codex['developerInstructions'])
        request=next(r['message'] for r in wire if r['engine']=='codex' and r['message'].get('method')=='turn/start')
        self.assertEqual(request['params']['effort'],'max')
        claude=next(r['instructions'] for r in wire if r['engine']=='claude' and 'Codex test reply' in r.get('instructions',''))
        self.assertIn('violet-731',claude)
        self.assertIn('codex-input-sentinel',claude,'Empty native turn snapshots must not lose the user prompt')
        ds=[r['message']['params'] for r in wire if r['engine']=='deepseek' and r['message'].get('method')=='session/prompt'][-1]
        self.assertIn('Codex test reply',json.dumps(ds,ensure_ascii=False));self.assertIn('测试完成',json.dumps(ds,ensure_ascii=False))
        self.stop_host();self.start_host()
        loaded=self.result('native/chat/thread/read',{'threadId':tid})['thread']
        self.assertEqual(loaded['turns'],doc['turns'])
        rows=self.result('native/threads/list',{'cwd':str(self.projects)})['data']
        self.assertEqual([row['id'] for row in rows],[tid])

    def test_shared_permission_request_maps_visible_conversation_and_can_be_answered(self):
        self.result('provider/save',{'engine':'claude','mode':'thirdparty','name':'Local',
            'baseUrl':'https://gateway.invalid','protocol':'anthropic-messages','models':['test-model'],'key':'private-test'})
        tid=self.result('native/chat/thread/start',{'engine':'claude','cwd':str(self.projects)})['thread']['id']
        self.result('native/chat/turn/start',{'threadId':tid,'engine':'claude','input':[{'type':'text','text':'permission'}]})
        request=next((e for e in self.events if e.get('type')=='approval'),None)
        while request is None:
            event=self.messages.get(timeout=5).get('event',{})
            if event.get('type')=='approval':request=event
        self.assertEqual(request['params']['threadId'],tid)
        pending=self.result('native/status')['approvals']
        self.assertEqual(pending[0]['params']['threadId'],tid)
        self.result('approvals/respond',{'id':request['id'],'result':{'decision':'decline'}})
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            doc=self.result('native/chat/thread/read',{'threadId':tid})['thread']
            if doc['turns'][-1]['status']=='completed':break
            time.sleep(.02)
        self.assertEqual(doc['turns'][-1]['status'],'completed')

    def test_live_retry_thinking_tool_progress_and_codex_output_arrive_before_completion(self):
        for engine in ('claude','codex'):
            self.result('provider/save',{'engine':engine,'mode':'thirdparty','name':'Local',
                'baseUrl':'https://gateway.invalid/v1','protocol':'anthropic-messages' if engine=='claude' else 'responses',
                'models':['test-model'],'key':'private-test'})
            tid=self.result('native/chat/thread/start',{'engine':engine,'cwd':str(self.projects)})['thread']['id']
            self.events.clear()
            self.result('native/chat/turn/start',{'threadId':tid,'engine':engine,'input':[{'type':'text','text':'progress-demo'}]})
            wanted='item/started' if engine=='claude' else 'item/commandExecution/outputDelta'
            def ready(e):
                p=e.get('params',{})
                return e.get('method')==wanted and (engine!='claude' or p.get('item',{}).get('elapsedSeconds')==2)
            seen=next((e for e in self.events if ready(e)),None)
            while seen is None:
                e=self.messages.get(timeout=5).get('event',{});self.events.append(e)
                if ready(e):seen=e
            self.assertEqual(seen['params']['threadId'],tid)
            live=self.result('native/chat/thread/read',{'threadId':tid})['thread']['turns'][-1]
            self.assertEqual(live['status'],'inProgress')
            self.assertEqual(live['progress']['phase'],'tool')
            if engine=='claude':
                retry=next(e['params']['progress'] for e in self.events if e.get('method')=='native/turnStatus'
                    and e.get('params',{}).get('progress',{}).get('phase')=='retrying')
                self.assertEqual(retry['httpStatus'],529);self.assertEqual(retry['maxRetries'],10)
                command=next(i for i in live['items'] if i['id']=='live-tool')
                self.assertEqual(command['command'],'echo live')
            else:
                command=next(i for i in live['items'] if i['id']=='live-command')
                self.assertEqual(command['aggregatedOutput'],'live\n')
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                final=self.result('native/chat/thread/read',{'threadId':tid})['thread']['turns'][-1]
                if final['status']=='completed':break
                time.sleep(.02)
            self.assertEqual(final['status'],'completed')
            phases=[p['phase'] for p in final['activity']]
            self.assertIn('tool',phases);self.assertIn('responding',phases)

    def test_credentials_stay_private_and_streamed_turn_is_readable(self):
        profile={'engine':'claude','mode':'thirdparty','name':'Local protocol test','baseUrl':'https://gateway.invalid',
            'protocol':'anthropic-messages','models':['test-model'],'key':'private-native-sentinel'}
        saved=self.result('provider/save',profile)
        self.assertNotIn(profile['key'],json.dumps(saved));self.assertTrue(saved['profiles']['thirdparty']['keyConfigured'])
        thread=self.result('thread/start',{'engine':'claude','cwd':str(self.projects),'model':'test-model'})['thread']
        tid=thread['id']
        self.result('turn/start',{'threadId':tid,'model':'test-model','input':[{'type':'text','text':'hello'}]})
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            message=self.messages.get(timeout=5)
            self.assertNotIn(profile['key'],json.dumps(message))
            if message.get('event',{}).get('method')=='turn/completed':break
        else:self.fail('Native stream did not deliver completed turn')
        data=self.result('native/thread/read',{'threadId':tid})['thread']
        self.assertEqual(data['turns'][0]['status'],'completed')
        self.assertTrue(any(i.get('text')=='测试完成' for i in data['turns'][0]['items']))

    def test_question_and_permission_responses_reach_agent_over_native_pipes(self):
        self.result('provider/save',{'engine':'claude','mode':'thirdparty','name':'Local','baseUrl':'https://gateway.invalid',
            'protocol':'anthropic-messages','models':['test-model'],'key':'private-test'})
        for prompt in ['question','permission']:
            tid=self.result('thread/start',{'engine':'claude','cwd':str(self.projects),'model':'test-model'})['thread']['id']
            self.result('turn/start',{'threadId':tid,'input':[{'type':'text','text':prompt}],'model':'test-model'})
            request=next((e for e in self.events if e.get('type')=='approval' and e['params']['threadId']==tid),None)
            while request is None:
                event=self.messages.get(timeout=5).get('event',{})
                if event.get('type')=='approval':request=event
            answer={'answers':{'q0':{'answers':['甲','补充内容']}}} if prompt=='question' else {'decision':'decline'}
            self.result('approvals/respond',{'id':request['id'],'result':answer})
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                status=self.result('native/thread/read',{'threadId':tid})['thread']['turns'][0]['status']
                if status=='completed':break
                time.sleep(.02)
            self.assertEqual(status,'completed')

    def test_permissions_control_both_agent_approval_channels(self):
        for engine in ['claude','deepseek']:
            with self.subTest(engine=engine):
                self.result('provider/save',{'engine':engine,'mode':'thirdparty','name':'Local',
                    'baseUrl':'https://gateway.invalid/v1','protocol':'anthropic-messages' if engine=='claude' else 'openai-completions',
                    'models':['test-model'],'key':'private-test'})
                self.result('native/permissions/set',{'engine':engine,'mode':'auto'})
                tid=self.result('thread/start',{'engine':engine,'cwd':str(self.projects),'model':'test-model'})['thread']['id']
                self.assertEqual(self.result('native/permissions/read',{'threadId':tid})['mode'],'auto')
                for mode in ['auto','ask']:
                    self.result('native/permissions/set',{'threadId':tid,'mode':mode})
                    self.events.clear()
                    self.result('turn/start',{'threadId':tid,'input':[{'type':'text','text':'permission'}],'model':'test-model'})
                    saw_approval=False
                    deadline=time.monotonic()+5
                    while time.monotonic()<deadline:
                        message=self.messages.get(timeout=5)
                        event=message.get('event',{})
                        if event.get('type')=='approval':
                            saw_approval=True
                            self.assertEqual(mode,'ask')
                            rejected=self.call('native/permissions/set',{'threadId':tid,'mode':'auto'})
                            self.assertIn('error',rejected)
                            self.result('approvals/respond',{'id':event['id'],'result':{'decision':'decline'}})
                        if event.get('method')=='turn/completed':break
                    else:self.fail('permission turn did not finish')
                    self.assertEqual(saw_approval,mode=='ask')
                if engine=='deepseek':
                    patch=json.loads((self.folder/'state/deepseek/permissions-ask.yml').read_text())
                    self.assertEqual(next(x for x in patch if x['id']=='sandbox-policy')['config']['mode'],'danger-full-access')
                    self.assertEqual(next(x for x in patch if x['id']=='approval')['config']['policy'],'ask')

    def test_missing_deepseek_launcher_has_installation_guidance(self):
        # Android sets an absolute launcher even before this optional Agent is installed.
        # A saved API model must not turn this into a raw subprocess ENOENT error.
        from unittest.mock import patch
        sys.path.insert(0, str(ROOT))
        from deepseek_bridge import DeepSeekBridge
        with patch.dict(os.environ, {'AI_XS_DEEPSEEK_COMMAND': json.dumps([str(self.folder/'missing-dsh')])}):
            bridge=DeepSeekBridge(type('App', (), {'_emit': lambda *args: None})(),self.folder/'missing-agent')
            with self.assertRaisesRegex(RuntimeError, '设置 → Agent 安装 → DeepSeek'):
                bridge.command(str(self.projects))

    def test_shared_projects_and_sessions_without_starting_codex(self):
        project=self.result('projects/create',{'name':'shared'})['project']
        ids={}
        for engine in ('claude','deepseek'):
            self.assertIn(project,self.result('projects/list',{'engine':engine})['data'])
            ids[engine]=self.result('thread/start',{'engine':engine,'cwd':project['path'],'model':'test-model'})['thread']['id']
        # The index is enough to list Codex sessions even with no executable.
        home=self.folder/'.codex';home.mkdir()
        with sqlite3.connect(home/'state_5.sqlite') as database:
            database.execute('CREATE TABLE threads (id TEXT, cwd TEXT, title TEXT, created_at INTEGER, updated_at INTEGER, archived INTEGER)')
            database.executemany('INSERT INTO threads VALUES (?,?,?,?,?,?)',[
                ('codex-local',project['path'],'old Codex',1,1,0),
                ('codex-archived',project['path'],'archived Codex',2,2,1)])
        page=self.result('native/threads/list',{'engine':'deepseek','limit':2})
        self.assertEqual(page['errors'],[])
        self.assertEqual(page['total'],3)
        self.assertEqual({row['engine'] for row in page['data']},{'claude','deepseek'})
        last=self.result('native/threads/list',{'cursor':page['nextCursor'],'limit':2})
        self.assertEqual([(row['id'],row['engine']) for row in last['data']],[('codex-local','codex')])
        self.assertIsNone(last['nextCursor'])
        self.assertEqual(self.result('native/threads/list',{'cwd':'/unrelated'})['data'],[])
        self.assertEqual(self.result('native/threads/list',{'searchTerm':'OLD CODEX'})['data'][0]['id'],'codex-local')
        self.assertEqual(self.result('native/threads/list',{'archived':True})['data'][0]['id'],'codex-archived')
        self.assertIsNone(self.result('native/usage/read',{'threadId':'codex-local'})['tokenUsage'])
        # Actions are routed by the session's owner, regardless of current Agent.
        self.result('thread/name/set',{'engine':'codex','threadId':ids['deepseek'],'name':'shared rename'})
        self.result('thread/archive',{'engine':'claude','threadId':ids['deepseek']})
        active=self.result('native/threads/list')['data']
        self.assertFalse(any(row['id']==ids['deepseek'] for row in active))
        archived=self.result('native/threads/list',{'archived':True})['data']
        self.assertEqual(next(row for row in archived if row['id']==ids['deepseek'])['name'],'shared rename')
        # One damaged index cannot hide the other Agents' histories.
        with sqlite3.connect(home/'state_5.sqlite') as database:database.execute('DROP TABLE threads')
        partial=self.result('native/threads/list')
        self.assertEqual(partial['errors'][0]['engine'],'codex')
        self.assertTrue(any(row['id']==ids['claude'] for row in partial['data']))

    def test_codex_usage_reads_index_and_rollout_without_launching_cli(self):
        home=self.folder/'.codex';home.mkdir()
        rollout=home/'rollout.jsonl'
        rollout.write_text(json.dumps({'type':'event_msg','payload':{'type':'token_count','info':{
            'last_token_usage':{'input_tokens':42,'output_tokens':8},
            'total_token_usage':{'input_tokens':100,'output_tokens':20},'model_context_window':1000}}})+'\n')
        with sqlite3.connect(home/'state_5.sqlite') as database:
            database.execute('CREATE TABLE threads (id TEXT, rollout_path TEXT)')
            database.execute('INSERT INTO threads VALUES (?,?)',('local-history',str(rollout)))
        result=self.result('native/usage/read',{'engine':'codex','threadId':'local-history','path':'/ignored'})
        self.assertEqual(result['tokenUsage']['last']['inputTokens'],42)
        self.assertEqual(result['tokenUsage']['total']['outputTokens'],20)
        self.assertIsNone(self.result('native/usage/read',{'threadId':'missing'})['tokenUsage'])


if __name__=='__main__':unittest.main()
