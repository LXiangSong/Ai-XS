"""Claude Code stream-json adapter, using the user's installed CLI and credentials."""
from __future__ import annotations
import base64
import copy
import datetime
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import subprocess
import threading
import time
import uuid


class ClaudeBridge:
    engine = 'claude'
    label = 'Claude'

    def __init__(self, app, folder):
        self.app = app
        self.folder = Path(folder)
        self.folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.runs = {}
        self.models = None
        self.status_cache = None
        self._native_scan_lock = threading.Lock()
        self._native_scan_running = False
        # A killed server cannot leave a task marked as running forever.
        for path in self.folder.glob('*.json'):
            if path.name == 'native-ignored.json':
                continue
            try:
                data = json.loads(path.read_text())
                changed = False
                for turn in data.get('turns', []):
                    if turn.get('status') == 'inProgress':
                        turn['status'] = 'interrupted'; changed = True
                if changed:
                    self._save(data)
            except (OSError, ValueError):
                continue

    def command(self, cwd):
        explicit = os.environ.get('AI_XS_CLAUDE_COMMAND')
        if explicit:
            command = json.loads(explicit)
            if not isinstance(command, list) or not command or not all(isinstance(x, str) for x in command):
                raise ValueError('AI_XS_CLAUDE_COMMAND 必须是 JSON 命令数组')
            if not shutil.which(command[0]):
                raise RuntimeError('Claude Code 尚未安装或启动程序已失效，请到设置 → Agent 安装 → Claude Code 安装或重新导入部署包')
            return command
        binary = shutil.which('claude')
        if not binary:
            raise RuntimeError('未找到 Claude Code，请在设置 → Agent 安装中安装')
        return [binary]

    def status(self):
        if self.status_cache:
            return self.status_cache
        try:
            result = subprocess.run(self.command(str(Path.home())) + ['--version'], capture_output=True, text=True, timeout=12)
            if result.returncode:
                raise RuntimeError((result.stderr or result.stdout).strip()[:500])
            self.status_cache = {'id': self.engine, 'name': self.label, 'available': True, 'version': result.stdout.strip()}
        except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
            self.status_cache = {'id': self.engine, 'name': self.label, 'available': False, 'error': str(error)}
        return self.status_cache

    def _path(self, thread_id):
        if not isinstance(thread_id, str) or not thread_id.startswith(self.engine+'-'):
            raise ValueError('无效的 Claude 会话 ID')
        uuid.UUID(thread_id[len(self.engine)+1:])
        return self.folder / (thread_id + '.json')

    def _read(self, thread_id):
        path = self._path(thread_id)
        if not path.exists():
            raise ValueError('Claude 会话不存在')
        return json.loads(path.read_text())

    def _save(self, data):
        path = self._path(data['id'])
        temp = path.with_suffix('.tmp')
        temp.write_text(json.dumps(data, ensure_ascii=False))
        temp.replace(path)

    def _thread(self, data):
        return {key: data[key] for key in ['id', 'name', 'preview', 'cwd', 'model', 'createdAt', 'updatedAt', 'archived', 'engine', 'forkedFromId', 'section', 'nativeSession', 'nativeOnly', 'source', 'importedFromNative', 'nativeNotice'] if key in data} | {'status': {'type': 'active' if data['id'] in self.runs else 'idle'}}

    # --- Native CLI sessions -------------------------------------------------
    # Ai-XS keeps its own transcript, but each CLI also persists sessions under
    # its own store. List those sessions so a project shows the same history
    # the CLI would show, and adopt one before resuming it here.
    def _native_sessions(self, background=False):
        now = time.monotonic()
        cache = getattr(self, '_native_cache', None)
        if cache and now - cache[0] < 20:
            return cache[1]
        if background:
            with self._native_scan_lock:
                if not self._native_scan_running:
                    self._native_scan_running = True
                    def scan():
                        try:
                            try:values = self._scan_native_sessions()
                            except Exception:values = []
                            self._native_cache = (time.monotonic(), values)
                            try:self.app._emit({'type':'notification','method':'native/threadsChanged','params':{'engine':self.engine}})
                            except Exception:pass
                        finally:
                            with self._native_scan_lock:self._native_scan_running = False
                    threading.Thread(target=scan, daemon=True, name=self.engine+'-history').start()
            return cache[1] if cache else []
        try:
            values = self._scan_native_sessions()
        except Exception:
            values = []
        self._native_cache = (now, values)
        return values

    def _native_thread_id(self, native_id):
        return self.engine + '-' + str(uuid.uuid5(uuid.NAMESPACE_URL, self.engine + ':' + str(native_id)))

    def _native_thread(self, native):
        thread_id = self._native_thread_id(native['nativeSession'])
        return {
            'id': thread_id, 'name': native.get('name') or (self.label + ' 原生会话'),
            'preview': native.get('preview', ''), 'cwd': native.get('cwd', ''),
            'model': native.get('model', ''), 'createdAt': native.get('createdAt') or time.time(),
            'updatedAt': native.get('updatedAt') or time.time(), 'archived': False,
            'engine': self.engine, 'nativeSession': native['nativeSession'], 'nativeStarted': True,
            'nativeOnly': True, 'source': 'native',
            'status': {'type': 'active' if thread_id in self.runs else 'idle'},
        }

    def _native_ignored(self):
        try:
            return set(json.loads((self.folder / 'native-ignored.json').read_text()))
        except (OSError, ValueError):
            return set()

    def _ignore_native(self, native_id):
        values = self._native_ignored()
        values.add(str(native_id))
        path = self.folder / 'native-ignored.json'
        temp = path.with_suffix('.tmp')
        temp.write_text(json.dumps(sorted(values), ensure_ascii=False))
        temp.replace(path)

    def _adopt_native(self, thread_id):
        for native in self._native_sessions():
            if self._native_thread_id(native['nativeSession']) != thread_id:
                continue
            if native['nativeSession'] in self._native_ignored():
                return None
            data = self._import_native(native)
            if data is None:
                return None
            self._save(data)
            return data
        return None

    @staticmethod
    def _claude_text(content):
        if isinstance(content, str):
            return content.strip()
        if isinstance(content, list):
            return ''.join(block.get('text', '') for block in content
                           if isinstance(block, dict) and block.get('type') == 'text').strip()
        return ''

    @staticmethod
    def _native_time(value, fallback):
        try:
            return datetime.datetime.fromisoformat(str(value).replace('Z', '+00:00')).timestamp()
        except (TypeError, ValueError):
            return fallback

    @staticmethod
    def _claude_breakdown(usage):
        if not isinstance(usage, dict):
            return {}
        input_tokens = int(usage.get('input_tokens') or 0) + int(usage.get('cache_read_input_tokens') or 0) + int(usage.get('cache_creation_input_tokens') or 0)
        output = int(usage.get('output_tokens') or 0)
        return {'inputTokens': input_tokens, 'outputTokens': output,
                'cachedInputTokens': int(usage.get('cache_read_input_tokens') or 0),
                'reasoningOutputTokens': 0, 'totalTokens': input_tokens + output}

    def _scan_native_sessions(self):
        root = Path.home() / '.claude' / 'projects'
        if not root.is_dir():
            return []
        found = {}
        for path in root.glob('*/*.jsonl'):
            try:
                stat = path.stat()
                if stat.st_size < 2:
                    continue
                meta = self._native_meta(path, stat)
            except Exception:
                continue
            if meta and meta['nativeSession'] not in found:
                found[meta['nativeSession']] = meta
        return list(found.values())

    def _native_meta(self, path, stat):
        session = path.stem
        cwd = ''
        first = ''
        summary = ''
        model = ''
        created = stat.st_mtime
        with path.open('r', encoding='utf-8', errors='replace') as stream:
            for index, line in enumerate(stream):
                if index > 500:
                    break
                if index > 120 and cwd and (first or summary):
                    break
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if row.get('isSidechain'):
                    continue
                session = row.get('sessionId') or session
                cwd = cwd or row.get('cwd') or ''
                message = row.get('message')
                if isinstance(message, dict):
                    model = model or message.get('model') or ''
                    if not first and row.get('type') == 'user':
                        text = self._claude_text(message.get('content'))
                        if text and not text.startswith(('<', 'Caveat:')):
                            first = re.sub(r'\s+', ' ', text)[:100]
                            created = self._native_time(row.get('timestamp'), created)
                if row.get('type') == 'summary' and row.get('summary'):
                    summary = str(row['summary']).strip()
        name = summary or first[:48] or ('Claude 会话 ' + session[:8])
        return {'nativeSession': session, 'cwd': cwd, 'name': name, 'preview': first or name,
                'model': model, 'createdAt': created, 'updatedAt': stat.st_mtime,
                'source': 'native', 'path': str(path)}

    def _import_native(self, native):
        path = Path(native.get('path') or '')
        if not path.is_file():
            return None
        data = {'id': self._native_thread_id(native['nativeSession']),
                'nativeSession': native['nativeSession'], 'nativeStarted': True,
                'engine': self.engine, 'cwd': native.get('cwd') or str(Path.home()),
                'name': native.get('name', ''), 'preview': native.get('preview', ''),
                'model': native.get('model') or 'default', 'turns': [],
                'createdAt': native.get('createdAt') or time.time(),
                'updatedAt': native.get('updatedAt') or time.time(),
                'archived': False, 'permissionMode': 'ask', 'importedFromNative': True}
        turns = []
        current = None
        tools = {}
        usage_total = {'inputTokens': 0, 'outputTokens': 0, 'cachedInputTokens': 0}
        last_usage = None
        capacity = None
        with path.open('r', encoding='utf-8', errors='replace') as stream:
            for line in stream:
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if row.get('isSidechain'):
                    continue
                kind = row.get('type')
                if kind == 'result':
                    for value in (row.get('modelUsage') or {}).values():
                        if isinstance(value, dict) and value.get('contextWindow'):
                            capacity = value['contextWindow']
                    continue
                message = row.get('message') if isinstance(row.get('message'), dict) else {}
                content = message.get('content')
                if kind == 'user':
                    text = self._claude_text(content)
                    if text and not text.startswith(('<', 'Caveat:')):
                        current = {'id': 'nt-' + uuid.uuid4().hex, 'status': 'completed', 'items': [
                            {'id': uuid.uuid4().hex, 'type': 'userMessage',
                             'content': [{'type': 'text', 'text': text}]}]}
                        turns.append(current)
                    elif isinstance(content, list):
                        for block in content:
                            if isinstance(block, dict) and block.get('type') == 'tool_result':
                                item = tools.get(block.get('tool_use_id'))
                                if item is not None:
                                    item['status'] = 'failed' if block.get('is_error') else 'completed'
                                    item['aggregatedOutput'] = self._claude_text(block.get('content'))
                elif kind == 'assistant' and current is not None:
                    usage = message.get('usage') or {}
                    for index, block in enumerate(message.get('content') or []):
                        if not isinstance(block, dict):
                            continue
                        if block.get('type') == 'text' and block.get('text'):
                            current['items'].append({'id': (message.get('id') or uuid.uuid4().hex) + '-' + str(index),
                                                     'type': 'agentMessage', 'text': block['text']})
                        elif block.get('type') == 'thinking' and block.get('thinking'):
                            current['items'].append({'id': (message.get('id') or uuid.uuid4().hex) + '-' + str(index),
                                                     'type': 'reasoning', 'summary': [], 'content': [block['thinking']]})
                        elif block.get('type') == 'tool_use':
                            name = block.get('name') or 'tool'
                            inputs = block.get('input') if isinstance(block.get('input'), dict) else {}
                            item = {'id': block.get('id') or uuid.uuid4().hex,
                                    'type': 'commandExecution' if name in ('Bash', 'PowerShell') else 'dynamicToolCall',
                                    'tool': name, 'arguments': inputs,
                                    'command': inputs.get('command', name), 'status': 'completed'}
                            current['items'].append(item)
                            tools[item['id']] = item
                    if usage:
                        last_usage = usage
                        usage_total['inputTokens'] += int(usage.get('input_tokens') or 0) + int(usage.get('cache_read_input_tokens') or 0) + int(usage.get('cache_creation_input_tokens') or 0)
                        usage_total['outputTokens'] += int(usage.get('output_tokens') or 0)
                        usage_total['cachedInputTokens'] += int(usage.get('cache_read_input_tokens') or 0)
                        data['model'] = message.get('model') or data['model']
                        data['nativeLastMessage'] = row.get('uuid') or data.get('nativeLastMessage')
        data['turns'] = turns
        if turns and not data['name']:
            data['name'] = data['preview'][:48]
        total = {key: value for key, value in usage_total.items() if value}
        if total:
            total['totalTokens'] = total.get('inputTokens', 0) + total.get('outputTokens', 0)
            data['tokenUsage'] = {'total': total, 'last': self._claude_breakdown(last_usage),
                                  'modelContextWindow': capacity}
            data['usageAt'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
        return data

    def _notify(self, method, params):
        self.app._emit({'type': 'notification', 'method': method, 'params': params})

    def _progress(self, run, phase, detail='', **extra):
        progress={'phase':phase,'detail':detail,'updatedAt':time.time(),**extra}
        prior=run['turn'].get('progress',{})
        # Do not emit a second status for every token in the same stream phase.
        if {k:v for k,v in prior.items() if k!='updatedAt'}=={k:v for k,v in progress.items() if k!='updatedAt'}:return
        run['turn']['progress']=progress
        self._notify('native/turnStatus',{'threadId':run['data']['id'],'turnId':run['turn']['id'],'progress':progress})

    def _spawn(self, cwd, arguments):
        env = dict(os.environ)
        env.pop('CLAUDECODE', None)
        env['CLAUDE_CODE_ENTRYPOINT'] = 'sdk-py'
        if getattr(self, 'mcp', None):
            self.mcp.prepare_claude()
            arguments = ['--mcp-config', str(self.mcp.claude_path)] + arguments
        if getattr(self, 'extensions', None):
            for plugin in self.extensions.claude_plugins(): arguments = ['--plugin-dir',plugin] + arguments
        if getattr(self,'providers',None):
            arguments=['--settings',str(self.providers.folder/'claude-provider.json')]+arguments
        return subprocess.Popen(self.command(cwd) + ['--print', '--input-format', 'stream-json', '--output-format', 'stream-json',
            '--verbose', '--permission-prompt-tool', 'stdio', '--include-partial-messages'] + arguments,
            cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding='utf-8', bufsize=1, start_new_session=True)

    @staticmethod
    def _kill(process):
        if process.poll() is None:
            try: os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError: pass
            try: process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                try: os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
                process.wait(timeout=3)
        for pipe in (process.stdin, process.stdout, process.stderr):
            if pipe:
                try: pipe.close()
                except OSError: pass

    def model_list(self):
        if self.models is not None:
            return {'data': self.models}
        if not self.status()['available']:
            raise RuntimeError(self.status().get('error', 'Claude 不可用'))
        process = self._spawn(str(Path.home()), [])
        messages = queue.Queue()
        def read():
            for line in process.stdout:
                try: messages.put(json.loads(line))
                except ValueError: pass
            messages.put(None)
        threading.Thread(target=read, daemon=True).start()
        # No prompt: initialize discovers CLI models without making a model call.
        try:
            process.stdin.write(json.dumps({'type': 'control_request', 'request_id': 'models', 'request': {'subtype': 'initialize', 'hooks': None}}) + '\n')
            process.stdin.flush()
            deadline = time.monotonic() + 20
            while True:
                message = messages.get(timeout=max(.1, deadline-time.monotonic()))
                if message is None:
                    raise RuntimeError('Claude 初始化退出，请检查本机 Claude 配置')
                if message.get('type') == 'control_response':
                    response = message['response']
                    if response.get('subtype') != 'success':
                        raise RuntimeError(response.get('error', 'Claude 初始化失败'))
                    data = response.get('response', {})
                    self.models = [{'id': m['value'], 'model': m['value'], 'displayName': m.get('displayName', m['value']),
                        'isDefault': m['value'] == 'default', 'inputModalities': ['text', 'image'], 'defaultReasoningEffort': 'high',
                        'supportedReasoningEfforts': [{'reasoningEffort': e} for e in ['low', 'medium', 'high', 'xhigh', 'max']]} for m in data.get('models', [])]
                    if not self.models:
                        raise RuntimeError('此 Claude 版本未返回模型列表')
                    return {'data': self.models}
        except queue.Empty as exc:
            raise RuntimeError('Claude 初始化超时') from exc
        finally:
            self._kill(process)

    def call(self, method, params):
        with self.lock:
            tid = params.get('threadId')
            if method == 'model/list': return self.model_list()
            if method == 'thread/list':
                values = []
                known = set()
                for path in self.folder.glob('*.json'):
                    if path.name == 'native-ignored.json':
                        continue
                    try: data = json.loads(path.read_text())
                    except (OSError, ValueError): continue
                    if data.get('nativeSession'): known.add(str(data['nativeSession']))
                    if bool(data.get('archived')) != bool(params.get('archived')): continue
                    cwd = params.get('cwd')
                    if cwd and data['cwd'] not in (cwd if isinstance(cwd, list) else [cwd]): continue
                    if params.get('sectionId', 'all') != 'all' and data.get('section', {}).get('id') != params['sectionId']: continue
                    if params.get('searchTerm', '').casefold() not in (data.get('name', '') + data.get('preview', '')).casefold(): continue
                    values.append(self._thread(data))
                if not params.get('archived'):
                    cwd = params.get('cwd')
                    needle = str(params.get('searchTerm', '')).casefold()
                    ignored = self._native_ignored()
                    for native in self._native_sessions(background=params.get('nativeDiscovery')=='background'):
                        native_id = str(native.get('nativeSession') or '')
                        if not native_id or native_id in known or native_id in ignored:
                            continue
                        if cwd and native.get('cwd') not in (cwd if isinstance(cwd, list) else [cwd]):
                            continue
                        text = (str(native.get('name', '')) + str(native.get('preview', ''))).casefold()
                        if needle and needle not in text:
                            continue
                        values.append(self._native_thread(native))
                return {'data': sorted(values, key=lambda t: t['updatedAt'], reverse=True), 'nextCursor': None}
            if method == 'thread/start':
                cwd = str(Path(params.get('cwd', str(Path.home()))).resolve())
                if not Path(cwd).is_dir(): raise ValueError('项目目录不存在')
                data = {'id': self.engine+'-' + str(uuid.uuid4()), 'nativeSession': str(uuid.uuid4()), 'nativeStarted': False, 'engine': self.engine,
                    'cwd': cwd, 'name': '', 'preview': '', 'model': params.get('model') or 'default', 'turns': [],
                    'developerInstructions': params.get('developerInstructions', ''), 'sandbox': params.get('sandbox'), 'approvalPolicy': params.get('approvalPolicy'), 'permissionMode': params.get('permissionMode', 'ask'),
                    'createdAt': time.time(), 'updatedAt': time.time(), 'archived': False}
                self._save(data)
                return {'thread': self._thread(data), 'approvalPolicy': 'on-request', 'sandbox': data.get('sandbox')}
            path = self._path(tid)
            if path.exists():
                data = json.loads(path.read_text())
            else:
                data = self._adopt_native(tid)
                if data is None:
                    raise ValueError('Claude 会话不存在')
            if method in ('thread/read', 'thread/resume'):
                if method == 'thread/resume' and params.get('developerInstructions') is not None:
                    data['developerInstructions'] = params['developerInstructions']; self._save(data)
                return {'thread': self._thread(data), 'approvalPolicy': 'on-request', 'sandbox': data.get('sandbox')}
            if method == 'thread/turns/list':
                turns = data['turns'] if params.get('sortDirection') == 'asc' else list(reversed(data['turns']))
                offset = int(params.get('cursor') or 0); limit = min(int(params.get('limit', 15)), 100)
                return {'data': [copy.deepcopy(turn) | {'itemsView': 'full'} for turn in turns[offset:offset+limit]], 'nextCursor': str(offset+limit) if len(turns)>offset+limit else None}
            if method == 'thread/items/list':
                turn = next(t for t in data['turns'] if t['id'] == params['turnId'])
                return {'data': [{'item': item} for item in turn['items']], 'nextCursor': None}
            if method == 'usage/read':
                return {'threadId': tid, 'tokenUsage': data.get('tokenUsage'), 'recordedAt': data.get('usageAt'), 'contextStale': False}
            if method == 'thread/name/set':
                data['name'] = str(params['name']); self._save(data)
                self._notify('thread/name/updated', {'threadId': tid, 'name': data['name']}); return {}
            if method == 'thread/section/move':
                data['section'] = {'id': params.get('sectionId')}; self._save(data); return {}
            if method in ('thread/archive', 'thread/unarchive', 'thread/delete'):
                if tid in self.runs: raise ValueError('请先停止 Claude 任务，再归档或删除')
                if method == 'thread/delete':
                    native_id = data.get('nativeSession')
                    if native_id: self._ignore_native(native_id)
                    self._path(tid).unlink(); return {}
                data['archived'] = method == 'thread/archive'; self._save(data); return {}
            if method in ('thread/revert', 'thread/fork'):
                if tid in self.runs: raise ValueError('请先停止 Claude 任务')
                turns = data['turns']
                target = params.get('beforeTurnId') if method == 'thread/revert' else params.get('lastTurnId')
                if target:
                    index = next((i for i,t in enumerate(turns) if t['id']==target), None)
                    if index is None: raise ValueError('目标轮次不存在')
                    turns = turns[:index] if method == 'thread/revert' else turns[:index+1]
                if method == 'thread/fork':
                    data['forkedFromId'] = tid; data['id'] = self.engine+'-' + str(uuid.uuid4()); data['createdAt'] = time.time(); data['name'] = (data['name'] or self.label+' 会话') + ' · 分叉'
                native_last = next((t.get('nativeLastMessage') for t in reversed(turns) if t.get('nativeLastMessage')), None)
                native_base = data['nativeSession'] if data.get('nativeStarted') else data.get('resumeFrom')
                if turns and native_last and native_base:
                    data['resumeFrom'] = native_base; data['resumeAt'] = native_last
                else:
                    data.pop('resumeFrom', None); data.pop('resumeAt', None)
                data['nativeSession'] = str(uuid.uuid4()); data['nativeStarted'] = False
                data['turns'] = turns; data.pop('tokenUsage', None); self._save(data)
                if method == 'thread/revert': self._notify('thread/reverted', {'threadId': tid})
                return {'thread': self._thread(data)}
            if method == 'turn/start':
                if params.get('permissionMode') is not None:
                    data.update(permissionMode=params['permissionMode'], approvalPolicy=params['approvalPolicy'], sandbox=params['sandbox'])
                return self._start(data, params)
            if method == 'turn/steer':
                run = self.runs.get(tid)
                if not run or run['turn']['id'] != params.get('expectedTurnId'): raise ValueError('Claude 当前任务已结束，请重新发送')
                if not run['ready']: raise ValueError('Claude 正在初始化，请稍后插入引导')
                self._content(params['input'])
                # stream-json user messages alone are queued by Claude. To guide
                # the current task, interrupt its current inference, then continue
                # the same native conversation with the new instruction.
                self._write(run, {'type':'control_request','request_id':'guide-'+uuid.uuid4().hex,'request':{'subtype':'interrupt'}})
                run.setdefault('guidance', []).append(params)
                item = {'id':uuid.uuid4().hex,'type':'userMessage','clientId':params.get('clientUserMessageId'),'content':params['input']}
                run['turn']['items'].append(item); self._save(run['data'])
                self._notify('item/completed', {'threadId':tid,'turnId':run['turn']['id'],'item':item})
                return {'turnId': run['turn']['id']}
            if method == 'turn/interrupt':
                run = self.runs.get(tid)
                if run and run['turn']['id'] == params.get('turnId'):
                    run['stopping'] = True
                    threading.Thread(target=self._stop_later, args=(tid,run), daemon=True).start()
                    try:self._write(run, {'type': 'control_request', 'request_id': 'stop-'+uuid.uuid4().hex, 'request': {'subtype': 'interrupt'}})
                    except (OSError,ValueError,RuntimeError):self._finish(run,'interrupted')
                return {}
            raise ValueError('Claude 暂不支持此操作：' + method)

    def _write(self, run, message):
        with run['writeLock']:
            if run['process'].poll() is not None: raise RuntimeError('Claude 进程已退出')
            run['process'].stdin.write(json.dumps(message, ensure_ascii=False) + '\n'); run['process'].stdin.flush()

    def _content(self, inputs):
        result = []
        for item in inputs:
            if item['type'] == 'text': result.append({'type': 'text', 'text': item['text']})
            elif item['type'] == 'localImage':
                path = Path(item['path']); raw = path.read_bytes()
                if len(raw)>15*1024*1024: raise ValueError('图片不能超过 15 MB')
                mime = 'image/png' if raw.startswith(b'\x89PNG') else 'image/jpeg' if raw.startswith(b'\xff\xd8') else 'image/gif' if raw.startswith(b'GIF8') else 'image/webp' if raw.startswith(b'RIFF') and raw[8:12]==b'WEBP' else None
                if not mime: raise ValueError('无效的图片格式')
                result.append({'type': 'image', 'source': {'type': 'base64', 'media_type': mime, 'data': base64.b64encode(raw).decode()}})
            elif item['type'] == 'image' and str(item.get('url', '')).startswith('https://'):
                result.append({'type': 'image', 'source': {'type': 'url', 'url': item['url']}})
            else: raise ValueError('Claude 不支持此输入类型：'+str(item.get('type')))
        return result

    def _send_input(self, run, params, record=True):
        content = self._content(params['input'])
        item = {'id': uuid.uuid4().hex, 'type': 'userMessage', 'clientId': params.get('clientUserMessageId'), 'content': params['input']}
        self._write(run, {'type': 'user', 'session_id': run['data']['nativeSession'], 'message': {'role': 'user', 'content': content}, 'parent_tool_use_id': None, 'uuid': str(uuid.uuid4())})
        if record:
            run['turn']['items'].append(item)
            self._save(run['data'])
            self._notify('item/completed', {'threadId': run['data']['id'], 'turnId': run['turn']['id'], 'item': item})

    def _start(self, data, params):
        tid = data['id']
        if tid in self.runs: raise ValueError('Claude 正在运行，请排队或插入引导')
        if data.get('archived'): raise ValueError('请先恢复已归档的会话')
        self._content(params['input'])  # Validate attachments before creating the turn/process.
        arguments = ['--model', params.get('model') or data['model'], '--effort', params.get('effort') or 'high', '--permission-mode', 'plan' if data.get('sandbox') == 'read-only' else 'manual', '--system-prompt-snapshot', 'off']
        instructions = data.get('developerInstructions') or ''
        if instructions:
            prompt_file = self.folder / (tid + '.instructions.txt')
            prompt_file.write_text(instructions); prompt_file.chmod(0o600)
            arguments += ['--append-system-prompt-file', str(prompt_file)]
        if data.get('nativeStarted'): arguments += ['--resume='+data['nativeSession']]
        elif data.get('resumeFrom'): arguments += ['--resume='+data['resumeFrom'], '--resume-session-at='+data['resumeAt'], '--fork-session', '--session-id', data['nativeSession']]
        else: arguments += ['--session-id', data['nativeSession']]
        process = self._spawn(data['cwd'], arguments)
        turn = {'id': 'ct-'+uuid.uuid4().hex, 'status': 'inProgress', 'items': []}
        data['turns'].append(turn); data['model'] = params.get('model') or data['model']; data['updatedAt'] = time.time()
        if not data['preview']: data['preview'] = next((p['text'][:100] for p in params['input'] if p['type']=='text'), '图片消息')
        run = {'process': process, 'data': data, 'turn': turn, 'writeLock': threading.Lock(), 'stopping': False, 'initialParams': params, 'stderr': deque_tail(), 'blocks': {}, 'ready': False}
        self.runs[tid] = run; self._save(data)
        self._notify('turn/started', {'threadId': tid, 'turn': copy.deepcopy(turn)})
        self._progress(run,'starting')
        threading.Thread(target=self._read_loop, args=(run,), daemon=True).start()
        threading.Thread(target=self._stderr, args=(run,), daemon=True).start()
        self._write(run, {'type': 'control_request', 'request_id': 'initialize', 'request': {'subtype': 'initialize', 'hooks': None}})
        threading.Thread(target=self._init_timeout, args=(tid,run), daemon=True).start()
        return {'turn': copy.deepcopy(turn)}

    def _stderr(self, run):
        try:
            for line in run['process'].stderr: run['stderr'].append(line[-1000:])
        except (OSError, ValueError): pass

    def _init_timeout(self, tid, run):
        time.sleep(30)
        with self.lock:
            if self.runs.get(tid) is run and not run['ready']:
                self._finish(run, 'failed', 'Claude 初始化超时，请检查本机 Claude 登录和配置')

    def _stop_later(self, tid, run):
        time.sleep(2)
        with self.lock:
            if self.runs.get(tid) is run: self._finish(run, 'interrupted')

    def _read_loop(self, run):
        try:
            for line in run['process'].stdout:
                try: message = json.loads(line)
                except ValueError: continue
                with self.lock:
                    if self.runs.get(run['data']['id']) is not run: break
                    self._handle(run, message)
        except (OSError, ValueError, RuntimeError) as error:
            with self.lock:
                if self.runs.get(run['data']['id']) is run: self._finish(run, 'failed', str(error))
        finally:
            with self.lock:
                if self.runs.get(run['data']['id']) is run:
                    self._finish(run, 'interrupted' if run['stopping'] else 'failed', ''.join(run['stderr'])[-1500:] or 'Claude 意外退出')

    def _item(self, run, item):
        prior = next((i for i in run['turn']['items'] if i['id']==item['id']), None)
        if prior: prior.update(item)
        else: run['turn']['items'].append(item)
        self._notify('item/completed', {'threadId': run['data']['id'], 'turnId': run['turn']['id'], 'item': item})

    def _handle(self, run, message):
        kind = message.get('type'); tid = run['data']['id']; turnid = run['turn']['id']
        if kind == 'control_response':
            response = message.get('response', {})
            if response.get('request_id') == 'initialize':
                if response.get('subtype') != 'success': self._finish(run, 'failed', response.get('error', 'Claude 初始化失败')); return
                run['ready'] = True; run['data']['nativeStarted'] = True
                self._send_input(run, run['initialParams'])
                self._progress(run,'waiting')
        elif kind == 'system':
            subtype=message.get('subtype')
            if subtype=='api_retry':
                self._progress(run,'retrying',attempt=message.get('attempt'),maxRetries=message.get('max_retries'),
                    retryDelayMs=message.get('retry_delay_ms'),httpStatus=message.get('error_status'))
            elif subtype=='status':
                phase={'requesting':'waiting','compacting':'compacting','thinking':'thinking'}.get(message.get('status'))
                if phase:self._progress(run,phase)
        elif kind == 'tool_progress':
            item=next((i for i in run['turn']['items'] if i['id']==message.get('tool_use_id')),None)
            if item:
                item['elapsedSeconds']=message.get('elapsed_time_seconds',0)
                self._notify('item/started',{'threadId':tid,'turnId':turnid,'item':copy.deepcopy(item)})
            self._progress(run,'tool',str(message.get('tool_name') or '工具')[:100])
        elif kind == 'control_request':
            request = message['request']; rid = message['request_id']
            if request.get('subtype') != 'can_use_tool':
                self._write(run, {'type':'control_response','response':{'subtype':'error','request_id':rid,'error':'Unsupported control request'}}); return
            tool = request.get('tool_name'); inputs = request.get('input', {})
            if tool != 'AskUserQuestion' and run['data'].get('permissionMode') == 'auto':
                self._write(run, {'type':'control_response','response':{'subtype':'success','request_id':rid,
                    'response':{'behavior':'allow','updatedInput':inputs}}})
                return
            approval_id = 'claude:'+tid+':'+rid
            if tool == 'AskUserQuestion':
                questions = [dict(q, id='q'+str(i), options=[{'label':o['label'], 'description':o.get('description','')} for o in q.get('options',[])]) for i,q in enumerate(inputs.get('questions',[]))]
                method = 'item/tool/requestUserInput'; params = {'threadId':tid, 'turnId':turnid, 'questions':questions, 'engine':'claude'}
            else:
                method = 'item/commandExecution/requestApproval'
                params = {'threadId':tid,'turnId':turnid,'tool':tool,'arguments':copy.deepcopy(inputs),
                    'command':inputs.get('command') or tool,'reason':request.get('description') or '',
                    'cwd':run['data']['cwd'],'engine':'claude'}
                if request.get('decision_reason'):
                    params['decisionReason'] = copy.deepcopy(request['decision_reason'])
            self.app.approvals[approval_id] = {'id':approval_id,'method':method,'params':params}
            self._progress(run,'approval')
            run.setdefault('requests', {})[approval_id] = {'rid':rid,'tool':tool,'input':inputs}
            self.app._emit({'type':'approval','id':approval_id,'method':method,'params':params})
        elif kind == 'control_cancel_request':
            self._clear_requests(run, message.get('request_id'))
        elif kind == 'stream_event' and not message.get('parent_tool_use_id'):
            event = message.get('event', {}); index = event.get('index', 0)
            if event.get('type') == 'message_start':
                run['messageId'] = event.get('message', {}).get('id', uuid.uuid4().hex); run['blocks'] = {}
                run['lastUsage'] = dict(event.get('message', {}).get('usage') or {})
                self._progress(run,'receiving')
            elif event.get('type') == 'message_delta':
                run.setdefault('lastUsage', {}).update(event.get('usage') or {})
            elif event.get('type') == 'content_block_start':
                block = event.get('content_block', {}); iid = run.get('messageId','stream')+'-'+str(index)
                if block.get('type') in ('text','thinking'):
                    item = {'id':iid, 'type':'reasoning' if block['type']=='thinking' else 'agentMessage', **({'summary':[], 'content':[]} if block['type']=='thinking' else {'text':block.get('text','')})}
                    run['blocks'][index] = item; run['turn']['items'].append(item)
                    self._notify('item/started', {'threadId':tid,'turnId':turnid,'item':copy.deepcopy(item)})
                    self._progress(run,'thinking' if block['type']=='thinking' else 'responding')
                elif block.get('type')=='tool_use':
                    tool=block.get('name','工具')
                    item={'id':block['id'],'type':'commandExecution' if tool in ('Bash','PowerShell') else 'dynamicToolCall',
                        'tool':tool,'arguments':block.get('input',{}),'command':tool,'status':'inProgress'}
                    run['blocks'][index]=item;run['turn']['items'].append(item)
                    run.setdefault('toolJson',{})[index]=''
                    self._notify('item/started',{'threadId':tid,'turnId':turnid,'item':copy.deepcopy(item)})
                    self._progress(run,'preparingTool',tool)
            elif event.get('type') == 'content_block_delta':
                item = run['blocks'].get(index); delta = event.get('delta', {})
                if item and delta.get('type') in ('text_delta','thinking_delta'):
                    text = delta.get('text',delta.get('thinking',''))
                    if item['type']=='reasoning': item['content'] = [(item['content'][0] if item['content'] else '')+text]
                    else: item['text'] += text
                    self._progress(run,'thinking' if item['type']=='reasoning' else 'responding')
                    self._notify('item/reasoning/textDelta' if item['type']=='reasoning' else 'item/agentMessage/delta', {'threadId':tid,'turnId':turnid,'itemId':item['id'],'contentIndex':0,'delta':text})
                elif item and delta.get('type')=='input_json_delta':
                    run.setdefault('toolJson',{})[index]=run.get('toolJson',{}).get(index,'')+delta.get('partial_json','')
                    try:inputs=json.loads(run['toolJson'][index])
                    except ValueError:return
                    if isinstance(inputs,dict):
                        item['arguments']=inputs;item['command']=inputs.get('command',item['tool'])
                        self._notify('item/started',{'threadId':tid,'turnId':turnid,'item':copy.deepcopy(item)})
            elif event.get('type')=='content_block_stop':
                item=run.get('blocks',{}).get(index)
                if item and item.get('tool'):self._progress(run,'tool',item['tool'])
        elif kind == 'assistant' and not message.get('parent_tool_use_id'):
            run['turn']['nativeLastMessage'] = message.get('uuid')
            msg = message.get('message', {})
            if msg.get('usage'): run['lastUsage'] = msg['usage']
            for index,block in enumerate(msg.get('content', [])):
                iid = msg.get('id',message.get('uuid',uuid.uuid4().hex))+'-'+str(index)
                if block.get('type')=='text': self._item(run, {'id':iid,'type':'agentMessage','text':block['text']})
                elif block.get('type')=='thinking': self._item(run, {'id':iid,'type':'reasoning','summary':[],'content':[block.get('thinking','')]})
                elif block.get('type')=='tool_use':
                    tool = block.get('name'); inp = block.get('input',{})
                    self._item(run, {'id':block['id'],'type':'commandExecution' if tool in ('Bash','PowerShell') else 'dynamicToolCall','tool':tool,'arguments':inp,'command':inp.get('command',tool),'status':'inProgress'})
                    self._progress(run,'tool',tool)
            self._save(run['data'])
        elif kind == 'user':
            for block in message.get('message',{}).get('content',[]) if isinstance(message.get('message',{}).get('content'),list) else []:
                if block.get('type')=='tool_result':
                    item = next((i for i in run['turn']['items'] if i['id']==block.get('tool_use_id')),None)
                    if item:
                        item.update({'status':'failed' if block.get('is_error') else 'completed', 'aggregatedOutput':block.get('content') if isinstance(block.get('content'),str) else json.dumps(block.get('content'),ensure_ascii=False),'result':block.get('content')}); self._item(run,item)
                        self._progress(run,'waiting')
        elif kind == 'result':
            usage = message.get('usage') or {}; model_usage = message.get('modelUsage') or {}
            def breakdown(value):
                tokens = {'inputTokens':value.get('input_tokens',0)+value.get('cache_read_input_tokens',0)+value.get('cache_creation_input_tokens',0),'outputTokens':value.get('output_tokens',0),'cachedInputTokens':value.get('cache_read_input_tokens',0)}
                tokens['totalTokens']=tokens['inputTokens']+tokens['outputTokens']; return tokens
            last = breakdown(run['lastUsage']) if run.get('lastUsage') else {}
            increment = breakdown(usage)
            previous = run['data'].get('tokenUsage',{}).get('total',{})
            total = {key:previous.get(key,0)+value for key,value in increment.items()}
            capacity = next((u.get('contextWindow') for u in model_usage.values() if u.get('contextWindow')),None)
            run['data']['tokenUsage'] = {'total':total,'last':last,'modelContextWindow':capacity}; run['data']['usageAt']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
            self._notify('thread/tokenUsage/updated', {'threadId':tid,'tokenUsage':run['data']['tokenUsage']})
            if run.get('guidance') and not run['stopping']:
                guidance = run.pop('guidance'); self._clear_requests(run)
                self._send_input(run, {'input':[part for entry in guidance for part in entry['input']]}, record=False)
                return
            error = message.get('is_error') or message.get('subtype','').startswith('error')
            self._finish(run, 'interrupted' if run['stopping'] else 'failed' if error else 'completed', '\n'.join(message.get('errors',[])) or (message.get('result') if error else None))

    def respond(self, approval_id, result):
        with self.lock:
            for run in self.runs.values():
                request = run.get('requests', {}).get(approval_id)
                if not request: continue
                inputs = copy.deepcopy(request['input'])
                if request['tool']=='AskUserQuestion':
                    answers = result.get('answers', {})
                    if result.get('cancelled'): response = {'behavior':'deny','message':'用户暂不回答，请根据已有信息继续或说明需要的信息'}
                    else:
                        inputs['answers'] = {question['question']: ', '.join(answers.get('q'+str(index),{}).get('answers',[])) for index,question in enumerate(inputs.get('questions',[]))}
                        response = {'behavior':'allow','updatedInput':inputs}
                else: response = {'behavior':'allow','updatedInput':inputs} if result.get('decision')=='accept' else {'behavior':'deny','message':'用户拒绝此操作'}
                self._write(run, {'type':'control_response','response':{'subtype':'success','request_id':request['rid'],'response':response}})
                self._progress(run,'tool',request['tool']) if request['tool']!='AskUserQuestion' and response.get('behavior')=='allow' else self._progress(run,'waiting')
                self._clear_requests(run,request['rid']); return
            raise ValueError('Claude 的提问或审批已结束')

    def _clear_requests(self, run, rid=None):
        for key,request in list(run.get('requests',{}).items()):
            if rid is None or request['rid']==rid:
                run['requests'].pop(key,None); self.app.approvals.pop(key,None)
                self._notify('serverRequest/resolved', {'threadId':run['data']['id'],'requestId':key})

    def _finish(self, run, status, error=None, cleanup=True):
        tid = run['data']['id']
        if self.runs.get(tid) is not run: return
        run['turn']['status']=status
        if error and status != 'interrupted': run['turn']['error']={'message':error}
        run['data']['updatedAt']=time.time(); self._save(run['data']); self._clear_requests(run)
        self.runs.pop(tid,None)
        self._notify('turn/completed', {'threadId':tid,'turn':copy.deepcopy(run['turn'])})
        if cleanup: threading.Thread(target=self._kill,args=(run['process'],),daemon=True).start()

    def close(self):
        with self.lock:
            processes = [run['process'] for run in self.runs.values()]
            for run in list(self.runs.values()): self._finish(run,'interrupted', cleanup=False)
        for process in processes:
            self._kill(process)


def deque_tail():
    from collections import deque
    return deque(maxlen=12)
