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
from conversation_usage import counters, add, model_id
from native_history import delete_claude, delete_deepseek
from native_usage import read_usage as read_native_usage


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
        self.cli_capabilities = {}
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
        def number(key):
            value=usage.get(key)
            return counters({'valueTokens':value}).get('valueTokens')
        result={}
        if number('input_tokens') is not None:
            result['inputTokens']=sum(number(k) or 0 for k in ('input_tokens','cache_read_input_tokens','cache_creation_input_tokens'))
        for key,target in (('output_tokens','outputTokens'),('cache_read_input_tokens','cachedInputTokens'),('cache_creation_input_tokens','cacheWriteInputTokens')):
            if number(key) is not None:result[target]=number(key)
        return counters(result)

    def _usage_update(self, run, result=None):
        """Stream snapshots and final native breakdown share one frozen baseline.

        Message IDs deduplicate streamed/final assistant messages. Only a result
        commits that baseline, so a final result never bills its stream twice.
        """
        baseline=run.setdefault('usageBaseline',copy.deepcopy(run['data'].get('tokenUsage') or {}))
        slots={}
        for entry in baseline.get('byModel') or []:slots[entry.get('model') or '']=copy.deepcopy(entry)
        if not slots and baseline.get('total'):
            slots['']={'model':None,'total':counters(baseline['total'])}
        increments={};lasts={}
        for entry in run.get('usageMessages',{}).values():
            name=entry.get('model') or ''
            tokens=self._claude_breakdown(entry.get('usage'))
            add(increments.setdefault(name,{}),tokens);lasts[name]=tokens
        native=(result or {}).get('modelUsage') or {}
        native_increments={}
        for name,entry in native.items():
            if not isinstance(entry,dict):continue
            raw={target:entry[key] for key,target in (('inputTokens','input_tokens'),('outputTokens','output_tokens'),
                ('cacheReadInputTokens','cache_read_input_tokens'),('cacheCreationInputTokens','cache_creation_input_tokens')) if key in entry}
            tokens=self._claude_breakdown(raw)
            if tokens:native_increments[name]=tokens
        if result is not None:
            aggregate=self._claude_breakdown(result.get('usage'))
            if native_increments:
                increments=native_increments
                # Preserve an unattributed remainder if a gateway omits models.
                summed={}
                for tokens in increments.values():add(summed,tokens)
                remainder={k:v-summed.get(k,0) for k,v in aggregate.items() if v>summed.get(k,0)}
                if remainder:increments['']=remainder
            elif aggregate:
                # Without a native breakdown, multiple reported models cannot
                # safely inherit the whole result's bill under the last model.
                names=set(increments)
                name=next(iter(names)) if len(names)==1 else '' if names else run['data'].get('model') or ''
                increments={name:aggregate}
        for name,tokens in increments.items():
            slot=slots.setdefault(name,{'model':model_id(name),'total':{}})
            add(slot.setdefault('total',{}),tokens)
        for name,last in lasts.items():
            slot=slots.setdefault(name,{'model':model_id(name),'total':{}});slot['last']=last
        for name,entry in native.items():
            if isinstance(entry,dict) and 'contextWindow' in entry:
                # Capacity-only reports still identify a window, never a bill.
                target=name if name in slots else (run.get('lastModel') or run['data'].get('model') or '') if len(native)==1 and not native_increments else name
                slots.setdefault(target,{'model':model_id(target),'total':{}})['modelContextWindow']=entry['contextWindow']
        total={}
        for slot in slots.values():add(total,slot.get('total'))
        last_model=run.get('lastModel') or run['data'].get('model') or ''
        active=slots.get(last_model,{})
        usage={'total':total,'last':self._claude_breakdown(run.get('lastUsage')),'modelContextWindow':active.get('modelContextWindow'),
            'byModel':list(slots.values())}
        run['data']['tokenUsage']=usage;run['data']['usageAt']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
        run['data']['usageModel']=last_model or None;run['data']['contextStale']=False
        self._refresh_native_usage(run['data'])
        usage=run['data']['tokenUsage']
        self._save(run['data'])
        self._notify('thread/tokenUsage/updated',{'threadId':run['data']['id'],'tokenUsage':usage,'model':last_model or None})
        if result is not None:
            run['usageBaseline']=copy.deepcopy(usage);run['usageMessages']={}

    def _usage_message(self, run, message):
        name=message.get('model') or run.get('lastModel') or run['data'].get('model')
        run['lastModel']=name
        key=message.get('id') or run.get('messageId') or 'unidentified'
        slot=run.setdefault('usageMessages',{}).setdefault(key,{'model':name,'usage':{}})
        slot['model']=name;slot['usage'].update(message.get('usage') or {})
        if slot['usage']:self._usage_update(run)

    def _scan_native_sessions(self):
        root = Path(os.environ.get('CLAUDE_CONFIG_DIR') or Path.home()/'.claude')/'projects'
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

    def _native_usage(self, data):
        session=data.get('nativeSession')
        if not session:return None
        try:uuid.UUID(session)
        except (ValueError,TypeError):return None
        root=Path(os.environ.get('CLAUDE_CONFIG_DIR') or Path.home()/'.claude')/'projects'
        for path in root.glob('*/'+session+'.jsonl'):
            if path.resolve().is_relative_to(root.resolve()):return read_native_usage(path,'claude')
        return None

    def _refresh_native_usage(self, data, checkpoint=False):
        """Reconcile one run against frozen native/live baselines, not two bills.

        Keep the checkpoint after completion: a delayed log flush can be read
        later without adding already streamed tokens a second time.
        """
        if self.engine!='claude':return
        try:native=self._native_usage(data)
        except (OSError,ValueError):return
        if native is None:
            if checkpoint:data['usageRecovery']={'native':{},'usage':copy.deepcopy(data.get('tokenUsage') or {})}
            return
        saved=copy.deepcopy(data.get('tokenUsage') or {})
        def groups(tokens):
            rows=tokens.get('byModel') or []
            if not rows and tokens.get('total'):rows=[{'model':None,'total':tokens['total']}]
            return {entry.get('model') or '':copy.deepcopy(entry) for entry in rows}
        slots=groups(saved);actual=groups(native['tokenUsage']);recovery=data.get('usageRecovery')
        base=groups((recovery or {}).get('usage') or {})
        before=groups((recovery or {}).get('native') or {})
        # Older builds have no per-run boundary. Recover a conservative lower
        # bound; unknown overlapping totals remain explicitly partial.
        legacy=not recovery and bool(saved.get('total'))
        if legacy and (not saved.get('byModel') or data.get('usageLegacyUnattributed')):
            # Replace an unattributed old aggregate with exact native models;
            # keep only its unrecoverable remainder, never add both bills.
            original=data.setdefault('usageLegacyUnattributed',copy.deepcopy(saved['total']))
            remainder={k:max(0,v-native['tokenUsage']['total'].get(k,0)) for k,v in counters(original).items()}
            slots={}
            if any(remainder.values()):slots['']={'model':None,'total':remainder}
        for name,entry in actual.items():
            slot=slots.setdefault(name,{'model':entry.get('model'),'total':{}})
            total=counters(slot.get('total'));b=counters(base.get(name,{}).get('total'));p=counters(before.get(name,{}).get('total'))
            for key,amount in counters(entry.get('total')).items():
                total[key]=max(total.get(key,0),b.get(key,0)+max(0,amount-p.get(key,0)))
            slot['total']=total
            if entry.get('recordedAt') and self._native_time(entry['recordedAt'],0)>=self._native_time(slot.get('recordedAt') or data.get('usageAt'),0):
                for key in ('last','recordedAt','contextStale'):
                    if key in entry:slot[key]=copy.deepcopy(entry[key])
        total={}
        for slot in slots.values():add(total,slot.get('total'))
        saved.update(total=total,byModel=list(slots.values()))
        if native.get('recordedAt') and self._native_time(native['recordedAt'],0)>=self._native_time(data.get('usageAt'),0):
            saved['last']=copy.deepcopy(native['tokenUsage']['last'])
            active=slots.get(native.get('model') or '',{})
            saved['modelContextWindow']=active.get('modelContextWindow')
            data.update(usageAt=native['recordedAt'],usageModel=native.get('model'),contextStale=native['contextStale'])
        if native['contextStale']:
            data['contextStale']=True
            if native.get('model') in slots:slots[native['model']]['contextStale']=True
        data['tokenUsage']=saved;data['nativeUsageRecovered']=True
        data['usagePartial']=bool(native.get('totalsPartial')) or bool(data.get('usagePartial')) or legacy and saved['total']!=native['tokenUsage']['total']
        if checkpoint:
            data['usageRecovery']={'native':copy.deepcopy(native['tokenUsage']),'usage':copy.deepcopy(saved)}
            data.pop('usageLegacyUnattributed',None)
        self._save(data)

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
        usage_total = {};usage_models={};seen_usage={}
        last_usage = None
        capacity = None;capacities={}
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
                    for name,value in (row.get('modelUsage') or {}).items():
                        if isinstance(value, dict) and value.get('contextWindow'):
                            capacities[name]=value['contextWindow']
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
                        normalized=self._claude_breakdown(usage);name=model_id(message.get('model'))
                        key=message.get('id') or row.get('uuid') or str(len(seen_usage))
                        previous=seen_usage.get(key,{})
                        increment={k:max(0,v-previous.get(k,0)) for k,v in normalized.items()}
                        seen_usage[key]={**previous,**normalized}
                        add(usage_total,increment)
                        slot=usage_models.setdefault(name or '',{'model':name,'total':{}})
                        add(slot['total'],increment);slot['last']=normalized
                        if name:current['model']=name
                        data['model'] = message.get('model') or data['model']
                        data['nativeLastMessage'] = row.get('uuid') or data.get('nativeLastMessage')
        data['turns'] = turns
        if turns and not data['name']:
            data['name'] = data['preview'][:48]
        for name,slot in usage_models.items():
            if name in capacities:slot['modelContextWindow']=capacities[name]
        capacity=capacities.get(data.get('model'))
        total = counters(usage_total)
        if total:
            data['tokenUsage'] = {'total': total, 'last': self._claude_breakdown(last_usage),'byModel':list(usage_models.values()),
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
        command=self.command(cwd);key=tuple(command)
        if key not in self.cli_capabilities:
            try:
                help_text=subprocess.run(command+['--help'],input='',capture_output=True,text=True,timeout=5,env=env).stdout
                self.cli_capabilities[key]={'hooks':'--include-hook-events' in help_text}
            except (OSError,subprocess.TimeoutExpired):self.cli_capabilities[key]={'hooks':False}
        if self.cli_capabilities[key]['hooks']:arguments=['--include-hook-events']+arguments
        return subprocess.Popen(command + ['--print', '--input-format', 'stream-json', '--output-format', 'stream-json',
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
                if tid in self.runs:data=self.runs[tid]['data']
                self._refresh_native_usage(data)
                return {'threadId': tid, 'tokenUsage': data.get('tokenUsage'), 'recordedAt': data.get('usageAt'),
                    'model':data.get('usageModel',data.get('model')),'contextStale':bool(data.get('contextStale')),
                    'totalsPartial':bool(data.get('usagePartial')),'nativeUsageRecovered':bool(data.get('nativeUsageRecovered'))}
            if method == 'thread/name/set':
                data['name'] = str(params['name']); self._save(data)
                self._notify('thread/name/updated', {'threadId': tid, 'name': data['name']}); return {}
            if method == 'thread/section/move':
                data['section'] = {'id': params.get('sectionId')}; self._save(data); return {}
            if method in ('thread/archive', 'thread/unarchive', 'thread/delete'):
                if tid in self.runs: raise ValueError('请先停止 Claude 任务，再归档或删除')
                if method == 'thread/delete':
                    if self.runs:raise ValueError('请先结束该 Agent 的任务，再完整清理原生历史')
                    native_id = data.get('nativeSession')
                    native_ids=list(dict.fromkeys((data.get('nativeSessions',[]) if self.engine=='deepseek' else [])+[native_id]))
                    for native in native_ids:
                        if native:
                            (delete_claude if self.engine=='claude' else delete_deepseek)(native,tid)
                            self._ignore_native(native)
                    self._path(tid).unlink(missing_ok=True); return {'deleted':True}
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
                data['turns'] = turns
                for key in ('tokenUsage','usageRecovery','usageLegacyUnattributed','nativeUsageRecovered','usageModel','usagePartial','usageAt','contextStale'):data.pop(key,None)
                self._save(data)
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
        self._refresh_native_usage(data,checkpoint=True)
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
        run = {'process': process, 'data': data, 'turn': turn, 'writeLock': threading.Lock(), 'stopping': False, 'initialParams': params, 'stderr': deque_tail(), 'blocks': {}, 'ready': False,
            'usageBaseline':copy.deepcopy(data.get('tokenUsage') or {})}
        self.runs[tid] = run; self._save(data)
        self._notify('turn/started', {'threadId': tid, 'turn': copy.deepcopy(turn), 'cwd':data['cwd']})
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
        item = dict(item)
        item.setdefault('status', 'completed')
        if 'arguments' in item:item.setdefault('partialInput','')
        if item.get('parentToolUseId') and item.get('type')=='agentMessage':item['type']='subagentMessage'
        if self.engine=='claude' and item.get('parentToolUseId'):
            parent=item['parentToolUseId']
            item.update(nativeChild=True,sourceEngine='claude',sourceThreadId=parent,sourceTurnId=run['turn']['id'],
                sourceModel=run.get('childModels',{}).get(parent))
        prior = next((i for i in run['turn']['items'] if i['id']==item['id']), None)
        if prior: prior.update(item)
        else: run['turn']['items'].append(item)
        method = 'item/started' if item['status'] in ('inProgress','pending','in_progress') else 'item/completed'
        self._notify(method, {'threadId': run['data']['id'], 'turnId': run['turn']['id'], 'item': copy.deepcopy(prior if prior else item)})
        return prior if prior else item

    def _discard_drafts(self, run, stream=None):
        for item in (stream.get('blocks',{}).values() if stream is not None else run['turn']['items']):
            if item.get('type')=='toolCallDraft':
                item.update(type='runtimeStatus',status='discarded',
                            tool='工具准备中断 · '+item.get('tool','工具')+' · 未执行',
                            progressMessage='参数生成未提交执行')
                self._item(run,item)

    def _handle(self, run, message):
        kind = message.get('type'); tid = run['data']['id']; turnid = run['turn']['id']
        parent=message.get('parent_tool_use_id')
        if parent:
            model=(message.get('message') or message.get('event',{}).get('message') or {}).get('model')
            if model:run.setdefault('childModels',{})[parent]=model
        stream=run if not parent else run.setdefault('childStreams',{}).setdefault(parent,{'blocks':{},'toolJson':{}})
        if kind == 'control_response':
            response = message.get('response', {})
            if response.get('request_id') == 'initialize':
                if response.get('subtype') != 'success': self._finish(run, 'failed', response.get('error', 'Claude 初始化失败')); return
                run['ready'] = True; run['data']['nativeStarted'] = True
                self._send_input(run, run['initialParams'])
                self._progress(run,'waiting')
        elif kind == 'system':
            subtype=message.get('subtype')
            if subtype in ('hook_started','hook_response','hook_progress'):
                iid='claude-hook:'+str(message.get('hook_id') or message.get('id') or message.get('hook_name') or message.get('hook_event') or 'unknown')
                prior=next((i for i in run['turn']['items'] if i['id']==iid),{})
                failed=message.get('exit_code') not in (None,0) or message.get('outcome') in ('error','failed','blocked')
                item=dict(prior) if prior else {'id':iid,'type':'hook','tool':message.get('hook_event') or message.get('hook_event_name') or message.get('hook_name') or 'Hook'}
                item['status']='inProgress' if subtype!='hook_response' else 'failed' if failed else 'completed'
                detail=message.get('output') or message.get('stdout') or message.get('stderr') or message.get('message')
                if detail:item['progressMessage']=str(detail)
                if parent:item['parentToolUseId']=parent
                self._item(run,item)
            elif subtype=='api_retry':
                self._progress(run,'retrying',attempt=message.get('attempt'),maxRetries=message.get('max_retries'),
                    retryDelayMs=message.get('retry_delay_ms'),httpStatus=message.get('error_status'))
            elif subtype in ('task_started','task_progress','task_notification','task_updated'):
                taskid=message.get('task_id')
                if taskid:
                    patch=message.get('patch') if subtype=='task_updated' else message
                    patch=patch if isinstance(patch,dict) else {}
                    iid='claude-task:'+str(taskid)
                    prior=next((i for i in run['turn']['items'] if i['id']==iid),{})
                    item=dict(prior) if prior else {'id':iid,'type':'taskProgress',
                        'tool':'子任务' if 'agent' in str(message.get('task_type','')) else '后台任务',
                        'progressMessage':'执行子任务','status':'inProgress'}
                    status=patch.get('status')
                    if status:item['status']={'running':'inProgress','in_progress':'inProgress','pending':'pending',
                        'completed':'completed','succeeded':'completed','failed':'failed','error':'failed',
                        'stopped':'interrupted','cancelled':'interrupted'}.get(status,status)
                    detail=patch.get('summary') or patch.get('description') or patch.get('last_tool_name')
                    if detail:item['progressMessage']=detail
                    for key in ('result','error'):
                        if patch.get(key) is not None:item[key]=copy.deepcopy(patch[key])
                    if message.get('tool_use_id'):item['parentToolUseId']=message['tool_use_id']
                    self._item(run,item)
                    child_parent=item.get('parentToolUseId')
                    if item.get('status')=='completed' and child_parent and detail and not any(
                            i.get('type')=='subagentMessage' and i.get('parentToolUseId')==child_parent for i in run['turn']['items']):
                        self._item(run,{'id':iid+':summary','type':'subagentMessage','text':str(detail),
                            'summaryOnly':True,'parentToolUseId':child_parent,'status':'completed'})
                    self._progress(run,'tool' if item['status'] in ('inProgress','pending') else 'waiting',item['tool'])
                    pending=run.get('pendingCompletion')
                    if pending and not any(i.get('type')=='taskProgress' and i.get('status') in ('inProgress','pending','running') for i in run['turn']['items']):
                        run.pop('pendingCompletion',None);self._finish(run,*pending)
            elif subtype=='status':
                phase={'requesting':'waiting','compacting':'compacting','thinking':'thinking'}.get(message.get('status'))
                if phase:self._progress(run,phase)
        elif kind == 'tool_progress':
            iid=message.get('tool_use_id')
            if iid:
                item=next((i for i in run['turn']['items'] if i['id']==iid),None)
                if item is None:
                    item={'id':iid,'type':'dynamicToolCall','tool':message.get('tool_name') or '工具','status':'inProgress'}
                if item.get('status','inProgress')=='inProgress':
                    if item.get('type')=='toolCallDraft':item['type']='commandExecution' if item.get('tool') in ('Bash','PowerShell') else 'dynamicToolCall'
                    item['elapsedSeconds']=message.get('elapsed_time_seconds',0)
                    item['progressMessage']='执行中 · '+str(item['elapsedSeconds'])+' 秒'
                    if parent:item['parentToolUseId']=parent
                    self._item(run,item)
            self._progress(run,'tool',str(message.get('tool_name') or '工具')[:100])
        elif kind == 'control_request':
            request = message['request']; rid = message['request_id']
            if request.get('subtype') != 'can_use_tool':
                self._write(run, {'type':'control_response','response':{'subtype':'error','request_id':rid,'error':'Unsupported control request'}}); return
            tool = request.get('tool_name'); inputs = request.get('input', {})
            iid=request.get('tool_use_id')
            if iid:self._item(run,{'id':iid,'type':'commandExecution' if tool in ('Bash','PowerShell') else 'dynamicToolCall',
                'tool':tool,'arguments':copy.deepcopy(inputs),'command':inputs.get('command',tool),'status':'inProgress'})
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
        elif kind == 'stream_event':
            event = message.get('event', {}); index = event.get('index', 0)
            if event.get('type') == 'message_start':
                self._discard_drafts(run,stream)
                stream['messageId'] = event.get('message', {}).get('id', uuid.uuid4().hex); stream['blocks'] = {}
                if not parent:run['lastUsage'] = dict(event.get('message', {}).get('usage') or {})
                if not parent:self._usage_message(run,event.get('message',{}))
                self._progress(run,'receiving')
            elif event.get('type') == 'message_delta':
                if not parent:run.setdefault('lastUsage', {}).update(event.get('usage') or {})
                if not parent:self._usage_message(run,{'id':stream.get('messageId'),'usage':event.get('usage') or {}})
            elif event.get('type') == 'content_block_start':
                block = event.get('content_block', {}); iid = stream.get('messageId','stream')+'-'+str(index)
                if block.get('type') in ('text','thinking'):
                    item = {'id':iid, 'type':'reasoning' if block['type']=='thinking' else 'agentMessage', 'status':'inProgress', **({'summary':[], 'content':[block.get('thinking','')]} if block['type']=='thinking' else {'text':block.get('text','')})}
                    if parent:item['parentToolUseId']=parent
                    stream['blocks'][index] = self._item(run,item)
                    self._progress(run,'thinking' if block['type']=='thinking' else 'responding')
                elif block.get('type') in ('tool_use','server_tool_use'):
                    tool=block.get('name','工具')
                    item={'id':block['id'],'type':'toolCallDraft',
                        'tool':tool,'arguments':block.get('input',{}),'command':tool,'status':'inProgress'}
                    if parent:item['parentToolUseId']=parent
                    stream.setdefault('toolJson',{})[index]=''
                    stream['blocks'][index]=self._item(run,item)
                    self._progress(run,'preparingTool',tool)
            elif event.get('type') == 'content_block_delta':
                item = stream['blocks'].get(index); delta = event.get('delta', {})
                if item and delta.get('type') in ('text_delta','thinking_delta'):
                    text = delta.get('text',delta.get('thinking',''))
                    if item['type']=='reasoning': item['content'] = [(item['content'][0] if item['content'] else '')+text]
                    else: item['text'] += text
                    self._progress(run,'thinking' if item['type']=='reasoning' else 'responding')
                    self._notify('item/reasoning/textDelta' if item['type']=='reasoning' else 'item/agentMessage/delta', {'threadId':tid,'turnId':turnid,'itemId':item['id'],'contentIndex':0,'delta':text})
                elif item and delta.get('type')=='input_json_delta':
                    stream.setdefault('toolJson',{})[index]=stream.get('toolJson',{}).get(index,'')+delta.get('partial_json','')
                    item['partialInput']=stream['toolJson'][index]
                    self._notify('item/started',{'threadId':tid,'turnId':turnid,'item':copy.deepcopy(item)})
                    try:inputs=json.loads(stream['toolJson'][index])
                    except ValueError:return
                    if isinstance(inputs,dict):
                        item['arguments']=inputs;item['command']=inputs.get('command',item['tool'])
                        item['partialInput']=''
                        self._notify('item/started',{'threadId':tid,'turnId':turnid,'item':copy.deepcopy(item)})
            elif event.get('type')=='content_block_stop':
                item=stream.get('blocks',{}).get(index)
                if item and item.get('tool'):self._progress(run,'tool',item['tool'])
                elif item:
                    item['status']='completed';self._item(run,item)
        elif kind == 'assistant':
            if not parent:run['turn']['nativeLastMessage'] = message.get('uuid')
            msg = message.get('message', {})
            if msg.get('usage') and not parent:run['lastUsage'] = msg['usage']
            if not parent:self._usage_message(run,msg)
            before=len(run['turn']['items'])
            for index,block in enumerate(msg.get('content', [])):
                iid = msg.get('id',message.get('uuid',uuid.uuid4().hex))+'-'+str(index)
                if block.get('type') in ('text','thinking'):
                    expected=('agentMessage','subagentMessage') if block['type']=='text' else ('reasoning',)
                    # CLI may emit one assistant snapshot per content block;
                    # enumerate(content) then resets to zero for every block.
                    base=msg.get('id',message.get('uuid',''))+'-'
                    candidates=[i for i in run['turn']['items'] if i['id'].startswith(base) and i.get('type') in expected]
                    current=next((i for i in run['turn']['items'] if i['id']==iid),None)
                    if len(msg.get('content',[]))==1 and candidates:
                        value=block.get('text',block.get('thinking',''))
                        match=next((i for i in candidates if (i.get('text') if block['type']=='text' else (i.get('content') or [''])[0])==value),None)
                        iid=(match or candidates[-1])['id']
                    elif current and current.get('type') not in expected:iid=base+block['type']+'-'+str(index)
                meta={'parentToolUseId':parent} if parent else {}
                if block.get('type')=='text': self._item(run, {'id':iid,'type':'agentMessage','text':block['text'],**meta})
                elif block.get('type')=='thinking': self._item(run, {'id':iid,'type':'reasoning','summary':[],'content':[block.get('thinking','')],**meta})
                elif block.get('type') in ('tool_use','server_tool_use'):
                    tool = block.get('name'); inp = block.get('input',{})
                    self._item(run, {'id':block['id'],'type':'commandExecution' if tool in ('Bash','PowerShell') else 'dynamicToolCall','tool':tool,'arguments':inp,'command':inp.get('command',tool),'status':'inProgress',**meta})
                    self._progress(run,'tool',tool)
                elif block.get('type') in ('tool_result','advisor_tool_result') or str(block.get('type','')).endswith('_tool_result'):
                    item=next((i for i in run['turn']['items'] if i['id']==block.get('tool_use_id')),None)
                    if item:
                        item.update(type='dynamicToolCall',status='failed' if block.get('is_error') else 'completed',result=block.get('content'),partialInput='',progressMessage='')
                        self._item(run,item)
            if parent:
                for child in run['turn']['items'][before:]:child['parentToolUseId']=parent
            self._save(run['data'])
        elif kind == 'user':
            for block in message.get('message',{}).get('content',[]) if isinstance(message.get('message',{}).get('content'),list) else []:
                if block.get('type')=='tool_result':
                    item = next((i for i in run['turn']['items'] if i['id']==block.get('tool_use_id')),None)
                    if item:
                        item['type']='commandExecution' if item.get('tool') in ('Bash','PowerShell') else 'dynamicToolCall'
                        item['partialInput']='';item['progressMessage']=''
                        item.update({'status':'failed' if block.get('is_error') else 'completed', 'aggregatedOutput':block.get('content') if isinstance(block.get('content'),str) else json.dumps(block.get('content'),ensure_ascii=False),'result':block.get('content')}); self._item(run,item)
                        self._progress(run,'waiting')
        elif kind == 'rate_limit_event':
            info=message.get('rate_limit_info') or {};status=info.get('status','unknown')
            self._item(run,{'id':'claude-rate-limit','type':'runtimeStatus','tool':'API 用量限制',
                'status':'failed' if status in ('rejected','blocked') else 'completed',
                'text':json.dumps({k:info[k] for k in ('status','resetsAt','rateLimitType','utilization','overageStatus','overageDisabledReason') if k in info},ensure_ascii=False)})
        elif kind == 'conversation_reset':
            if message.get('new_conversation_id'):run['data']['nativeSession']=message['new_conversation_id']
            self._item(run,{'id':'claude-context-reset','type':'runtimeStatus','tool':'上下文已重置','status':'completed'})
        elif kind == 'result':
            self._usage_update(run,message)
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
        if self.engine=='claude' and status=='completed' and any(i.get('type')=='taskProgress' and i.get('status') in ('inProgress','pending','running') for i in run['turn']['items']):
            run['pendingCompletion']=(status,error,cleanup);self._progress(run,'tool','等待后台任务完成');return
        self._discard_drafts(run)
        prior_usage=copy.deepcopy(run['data'].get('tokenUsage'))
        self._refresh_native_usage(run['data'])
        run['turn']['status']=status
        for item in run['turn'].get('items',[]):
            if item.get('status') in ('inProgress','pending','in_progress'):
                item['status']='completed' if status=='completed' else status
        if error and status != 'interrupted': run['turn']['error']={'message':error}
        run['data']['updatedAt']=time.time(); self._save(run['data']); self._clear_requests(run)
        self.runs.pop(tid,None)
        if run['data'].get('tokenUsage')!=prior_usage:
            self._notify('thread/tokenUsage/updated',{'threadId':tid,'tokenUsage':copy.deepcopy(run['data']['tokenUsage']),
                'model':run['data'].get('usageModel',run['data'].get('model')),'totalsPartial':bool(run['data'].get('usagePartial')),
                'contextStale':bool(run['data'].get('contextStale'))})
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
