#!/usr/bin/env python3
"""Shared Agent adapters for the Ai-XS private process service."""

from __future__ import annotations

import base64
from collections import deque
import json
import os
import queue
import secrets
import signal
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from claude_bridge import ClaudeBridge
from deepseek_bridge import DeepSeekBridge
from native_usage import read_usage as read_native_usage
from mcp_manager import McpManager
from assistant_tools import extensions
from engine_manager import EngineManager
from local_tools import LocalTools
from provider_manager import ProviderManager
from permission_modes import PermissionModes, apply as permission_params, validate as validate_permission
from file_reports import TurnFileReports
from native_children import CodexChildren

ROOT = Path(__file__).resolve().parent
UPLOADS = ROOT / ".uploads"
UPLOADS.mkdir(exist_ok=True)
STATE = Path(os.environ.get("AI_XS_STATE_DIR", str(ROOT / ".state")))
STATE.mkdir(mode=0o700, exist_ok=True)
PERMISSIONS = PermissionModes(STATE)


class AppServer:
    def __init__(self):
        self.lock = threading.RLock()
        self.write_lock = threading.Lock()
        self.pending: dict[int, queue.Queue] = {}
        self.approvals: dict[str, dict] = {}
        self.events = deque(maxlen=4000)
        self.event_condition = threading.Condition()
        self.event_id = 0
        self.next_id = 0
        self.process: subprocess.Popen | None = None
        self.active_turns = {}
        self.user_agent = 'Codex'
        self.lifecycle_lock = threading.RLock()
        self.reader = None
        self.local_tools = LocalTools(self._emit)
        self.file_reports = TurnFileReports((STATE, UPLOADS))
        self.codex_children = CodexChildren(self._watch_child,STATE/'codex-children')

    def _watch_child(self, tid):
        def watch():
            try:
                for attempt in range(4):
                    try:
                        result=self.call('thread/resume',{'threadId':tid},timeout=20);break
                    except RuntimeError as error:
                        if attempt==3 or not any(t in str(error).lower() for t in ('no rollout','not found')):raise
                        time.sleep((.1,.3,.8)[attempt])
                for event in self.codex_children.replay(result.get('thread'),result.get('model')):self._emit(event)
            except Exception as error:
                self._codex_event({'type':'notification','method':'warning','params':{
                    'threadId':tid,'message':'子任务实时订阅失败：'+str(error)[:300]}})
        threading.Thread(target=watch,name='codex-child-watch',daemon=True).start()

    def _codex_event(self, event):
        for projected in self.codex_children.observe(event):self._emit(projected)

    def start(self):
        with self.lifecycle_lock:
            try:self._start()
            except Exception:
                self._stop()
                raise

    def _start(self):
        if self.process and self.process.poll() is None:
            return
        extra,env=self.providers.codex_launch() if getattr(self,'providers',None) else ([],None)
        self.process = subprocess.Popen(
            ["codex", *extra, "app-server", "--stdio"], env=env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=sys.stderr if getattr(self,'diagnostics',False) else subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            bufsize=1,
            start_new_session=True,
        )
        process = self.process
        self.reader = threading.Thread(target=self._read_loop, args=(process,), daemon=True)
        self.reader.start()
        initialized = self.call("initialize", {
            "clientInfo": {"name": "ai_xs_native", "title": "Ai-XS", "version": "0.5.0-alpha"},
            "capabilities": {"experimentalApi": True},
        })
        self.notify("initialized", {})
        self.user_agent = initialized.get("userAgent", "Codex")
        if getattr(self, 'mcp', None): self.mcp.sync_codex()
        if getattr(self, 'providers', None):
            try:self.providers.model_list('codex',self.call('model/list',{},timeout=5))
            except Exception:pass  # Cached capabilities remain available offline.

    def _emit(self, event):
        try:self.file_reports.observe(event)
        except Exception as error:
            # File reporting must never hide an Agent result or its final event.
            if event.get('method')=='turn/completed':
                event.get('params',{}).get('turn',{}).setdefault('items',[]).append({
                    'id':'aixs-file-report','type':'fileReport','status':'failed',
                    'partial':True,'text':'文件清单检查失败：'+type(error).__name__})
        with self.event_condition:
            self.event_id += 1
            event["seq"] = self.event_id
            self.events.append(event)
            self.event_condition.notify_all()

    def _write(self, data):
        process = self.process
        if not process or process.poll() is not None or not process.stdin:
            raise RuntimeError("Codex app-server 已退出")
        with self.write_lock:
            process.stdin.write(json.dumps(data, ensure_ascii=False) + "\n")
            process.stdin.flush()

    def _read_loop(self, process):
        assert process.stdout
        for line in process.stdout:
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                continue
            if "id" in message and ("result" in message or "error" in message):
                result=message.get('result') or {}
                if isinstance(result,dict):
                    self.file_reports.bind(result.get('thread'))
                    self.codex_children.bind(result.get('thread'),result.get('model'))
                with self.lock:
                    waiter = self.pending.get(message["id"])
                if waiter:
                    try:waiter.put_nowait(message)
                    except queue.Full:pass
            elif "method" in message and "id" in message:
                approval_id = str(message["id"])
                self.approvals[approval_id] = message
                self._codex_event({"type": "approval", "id": approval_id, "method": message["method"], "params": message.get("params", {})})
            elif "method" in message:
                params = message.get('params', {})
                if message['method'] == 'turn/started':
                    self.active_turns[params['threadId']] = params['turn']['id']
                elif message['method'] == 'turn/completed' and self.active_turns.get(params.get('threadId')) == params.get('turn', {}).get('id'):
                    self.active_turns.pop(params['threadId'], None)
                if message["method"] == "serverRequest/resolved":
                    self.approvals.pop(str(message.get("params", {}).get("requestId")), None)
                self._codex_event({"type": "notification", "method": message["method"], "params": message.get("params", {})})
        if self.process is not process:
            return
        with self.lock:
            for waiter in self.pending.values():
                try:
                    waiter.put_nowait({"error": {"message": "Codex app-server 已断开"}})
                except queue.Full:
                    pass
        self._interrupt_turns()
        self._emit({'type':'notification','method':'engines/changed','params':{'engine':'codex'}})

    def _interrupt_turns(self):
        for tid, turn_id in list(self.active_turns.items()):
            self._codex_event({'type':'notification','method':'turn/completed','params':{'threadId':tid,'turn':{'id':turn_id,'status':'interrupted','items':[]}}})
        self.active_turns.clear()

    def stop(self):
        with self.lifecycle_lock:self._stop()

    def _stop(self):
        process = self.process
        if process and process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=3)
            except ProcessLookupError:
                pass
        if self.reader and self.reader is not threading.current_thread():
            self.reader.join(timeout=3)
        self._interrupt_turns()
        for request_id, request in list(self.approvals.items()):
            if not str(request_id).startswith(('claude:','deepseek:')):
                self.approvals.pop(request_id, None)
                self._emit({'type':'notification','method':'serverRequest/resolved','params':{'requestId':request['id']}})
        if process:
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream:
                    stream.close()
        self.process = None

    def call(self, method, params=None, timeout=120):
        if method=='turn/start' and params:self.codex_children.set_model(params.get('threadId'),params.get('model'))
        if method=='turn/interrupt' and params:
            tid=params.get('threadId');turnid=params.get('turnId')
            children=self.codex_children.active_children(tid,turnid)
            self.codex_children.interrupt(tid,turnid)
            for child,child_turn in children:
                if child!=tid:
                    try:
                        self.call('turn/interrupt',{'threadId':child,'turnId':child_turn},timeout=min(timeout,5))
                    except Exception as error:
                        # Children can finish between the activity snapshot and
                        # this RPC. Their rejection must not block the parent.
                        self._codex_event({'type':'notification','method':'warning','params':{
                            'threadId':child,'turnId':child_turn,
                            'message':'子任务停止请求未确认，继续停止主任务：'+str(error)[:300]}})
        with self.lock:
            self.next_id += 1
            request_id = self.next_id
            waiter = queue.Queue(maxsize=1)
            self.pending[request_id] = waiter
        try:
            self._write({"id": request_id, "method": method, "params": params or {}})
            try:
                response = waiter.get(timeout=timeout)
            except queue.Empty as exc:
                raise TimeoutError(f"Codex 请求超时: {method}") from exc
            if "error" in response:
                raise RuntimeError(response["error"].get("message", "Codex 请求失败"))
            return response.get("result", {})
        finally:
            with self.lock:
                self.pending.pop(request_id, None)

    def notify(self, method, params=None):
        self._write({"method": method, "params": params or {}})

    def respond(self, request_id, result):
        message = self.approvals.pop(str(request_id), None)
        if not message:
            raise KeyError("审批请求已结束")
        self._write({"id": message["id"], "result": result})
        self._emit({"type": "resolved", "id": str(request_id)})


APP: AppServer | None = None
CLAUDE: ClaudeBridge | None = None
DEEPSEEK: DeepSeekBridge | None = None
MCP: McpManager | None = None
ENGINES = None
PROVIDERS = None
EXTENSIONS = None


def shared_instructions(params):
    """Shared project requirements never depend on the selected assistant."""
    with APP.lock:
        value = read_ui_state()
    parts = [value.get('settings', {}).get('instructions', ''),params.get('developerInstructions') or '']
    if EXTENSIONS:
        parts.append(EXTENSIONS.instructions(params.get('engine','codex')))
    if MCP and 'aixs-device' in MCP.read():
        parts.append('需要用户选择方案或补充信息时，使用 aixs-device MCP 的 ask_user 工具（或原生提问工具）显示可交互选项并等待回答；不要只在回复正文中列出选项。ask_user 支持单选、多选和文字回答；多个问题一次传 questions 数组，让用户能够上一步/下一步重选，提交全部后再继续。')
        parts.append('用户要求安装 Skills 或插件时，优先使用 aixs-device 的 extensions_preview（GitHub）或 extensions_import（本地目录/ZIP/SKILL.md），再用 extensions_install 安装并选择 agents。用 extensions_list 查看已安装和兼容性，用 extensions_configure 分配或停用。不要把安装到单个 Agent 的原生目录误报为全部 Agent 可用；不兼容时说明返回的原因。新安装的扩展从下一次任务开始加载。')
    cwd = str(Path(params.get('cwd') or value.get('cwd') or Path.home()).resolve())
    project = next((p for p in sorted(value.get('projects', []), key=lambda p: len(p['path']), reverse=True) if Path(cwd) == Path(p['path']) or Path(p['path']) in Path(cwd).parents), None)
    requirement = value.get('projectRequirements', {}).get(project['path'] if project else cwd, '')
    if requirement:
        parts.append('项目共同要求（切换助手后仍需遵守）：\n' + requirement)
    # Read the same repository instructions for both assistants. Native loaders
    # still handle their normal nested instruction discovery.
    for filename in ('AGENTS.md', 'CLAUDE.md'):
        path = Path(cwd) / filename
        if path.is_file() and path.stat().st_size <= 64 * 1024:
            parts.append(filename + '：\n' + path.read_text(errors='replace'))
    return '\n\n'.join(part for part in parts if part)


def save_ui_state(value):
    if len(json.dumps(value)) > 2 * 1024 * 1024:
        raise ValueError("界面设置过大")
    temporary = STATE / "ui.tmp"
    temporary.write_text(json.dumps(value, ensure_ascii=False))
    temporary.replace(STATE / "ui.json")


def read_ui_state():
    path = STATE / "ui.json"
    value = json.loads(path.read_text()) if path.exists() else {}
    if value.get("schemaVersion", 0) < 2:
        # Earlier builds wrongly treated shared-storage folders as projects.
        # Retain only direct children of the default workspace during migration.
        workspace = Path(os.environ.get("CODEX_ZH_PROJECTS", str(Path.home() / "projects"))).resolve()
        candidates = value.get("projects", [])
        if not candidates and workspace.is_dir():
            candidates = [{"path": str(p), "name": p.name} for p in workspace.iterdir() if p.is_dir() and not p.name.startswith((".", "__"))]
        projects = {}
        for project in candidates:
            folder = Path(project.get("path", "")).resolve()
            if folder.parent == workspace and folder.is_dir() and not folder.name.startswith((".", "__")):
                projects[str(folder)] = {"path": str(folder), "name": project.get("name") or folder.name}
        if path.exists() and not (STATE / "ui-before-workspace-v2.json").exists():
            (STATE / "ui-before-workspace-v2.json").write_text(path.read_text())
        value.update({"schemaVersion": 2, "workspace": {"path": str(workspace), "name": workspace.name},
                      "projects": list(projects.values()), "scope": "all", "modelChanges": value.get("modelChanges", {}),
                      "modelPrefs": value.get("modelPrefs", {})})
        save_ui_state(value)
    return value


def recent_rollout_rows(path, limit=16 * 1024 * 1024):
    """Read recent JSONL records backwards without loading a long conversation."""
    with path.open("rb") as stream:
        position = stream.seek(0, os.SEEK_END)
        remaining = limit
        pending = b""
        while position and remaining:
            size = min(position, remaining, 65536)
            position -= size
            remaining -= size
            stream.seek(position)
            lines = (stream.read(size) + pending).split(b"\n")
            pending = lines[0]
            for line in reversed(lines[1:]):
                if line:
                    yield line
        if position == 0 and pending:
            yield pending


def read_thread_usage(thread_id, local_thread=None):
    if not isinstance(thread_id, str) or not thread_id or len(thread_id) > 100:
        raise ValueError("缺少有效的会话 ID")
    # Only trusted local index data or Codex itself can resolve rollout paths.
    thread = local_thread if local_thread is not None else APP.call("thread/read", {"threadId": thread_id, "includeTurns": False})["thread"]
    result = {"threadId": thread_id, "tokenUsage": None, "recordedAt": None, "contextStale": False, "compactedAt": None}
    if not thread.get("path"):
        return result
    path = Path(thread["path"])
    if not path.is_file():
        return result
    recovered=read_native_usage(path,'codex')
    if recovered['tokenUsage']['byModel']:
        result.update(recovered)
    return result


def engine_for(params):
    tid=str(params.get('threadId',''))
    return 'deepseek' if tid.startswith('deepseek-') else 'claude' if tid.startswith('claude-') else params.get('engine','codex') if not tid else 'codex'


def api_route(method, params):
    engine = engine_for(params)
    if method in ('native/permissions/read', 'native/permissions/set'):
        if engine not in ('codex', 'claude', 'deepseek'):raise ValueError('未知的 AI 助手')
        tid = params.get('threadId', '')
        if method.endswith('/read'):return {'mode': PERMISSIONS.mode(engine, tid)}
        with ENGINES.lock if ENGINES else APP.lock:
            active = APP.active_turns if engine == 'codex' else {'claude':CLAUDE, 'deepseek':DEEPSEEK}[engine].runs
            if active or ENGINES and (ENGINES.inflight[engine] or engine in ENGINES.busy):
                raise ValueError('请先等待或停止任务，再切换权限模式')
            return PERMISSIONS.set(engine, params.get('mode'), tid)
    def invoke():
        inputs = params
        mode = None
        if method in ('thread/start', 'thread/resume', 'turn/start'):
            mode = PERMISSIONS.mode(engine, params.get('threadId', ''))
            inputs = permission_params(params, mode, method, engine)
        result = _api_route(method, inputs)
        if method == 'thread/start':
            PERMISSIONS.set(engine, mode, result['thread']['id'])
        return result
    uses_cli = method in ('thread/start','thread/resume','turn/start','turn/steer','model/list') if engine!='codex' else method=='usage/read' or method.startswith(('thread/','threadSection/','turn/','model/','review/','config/','skills/','plugin/','marketplace/','account/','mcpServer/','mcpServerStatus/'))
    if ENGINES and uses_cli:
        # Metadata requests (model/list, thread/list, thread/read, ...) must not
        # stop the other agent's idle process.  Only an actual turn activates the
        # single-runtime switch on this mobile build.
        activate = method in ('turn/start','turn/steer')
        with ENGINES.request(engine, activate):return invoke()
    return invoke()


def _api_route(method, params):
    if APP is None:
        raise RuntimeError("Codex 尚未启动")
    params = dict(params)
    engine = engine_for(params)
    params.pop('engine',None)
    if engine not in ('codex', 'claude', 'deepseek'):
        raise ValueError('未知的 AI 助手')
    if method.startswith('provider/'):
        if PROVIDERS is None:raise RuntimeError('API 服务配置尚未初始化')
        if method=='provider/read':return PROVIDERS.read(engine)
        if method=='provider/discover':return PROVIDERS.discover(engine,params)
        if method!='provider/save':raise ValueError('不支持的 API 配置操作')
        # Block new submissions while replacing provider configuration. Active
        # turns keep their original credentials and must finish first.
        with ENGINES.lock if ENGINES else APP.lock:
            if ENGINES and (engine in ENGINES.busy or ENGINES.inflight[engine]):raise ValueError('该助手正在提交请求或执行管理操作，请稍后保存')
            running=APP.active_turns if engine=='codex' else {'claude':CLAUDE,'deepseek':DEEPSEEK}[engine].runs
            if running:raise ValueError('请先结束该助手正在运行的任务，再切换 API 服务')
            before=PROVIDERS.document();alive=bool(APP.process and APP.process.poll() is None)
            try:
                PROVIDERS.save(engine,params)
                if engine=='codex' and alive:APP.stop();APP.start()
                elif engine!='codex':
                    bridge={'claude':CLAUDE,'deepseek':DEEPSEEK}[engine]
                    bridge.models=None
            except ValueError:
                raise
            except Exception:
                PROVIDERS.restore(before)
                if engine=='codex' and alive:
                    try:APP.start()
                    except Exception:pass
                raise RuntimeError('API 配置加载失败，已恢复之前的配置') from None
            return {**PROVIDERS.read(engine),'detail':'已保存，下次发送使用所选 API；原有会话保留。'}
    if method in ('engines/status', 'engines/action', 'engines/job'):
        if ENGINES is None:raise RuntimeError('助手管理尚未初始化')
        if method == 'engines/status':return ENGINES.status(bool(params.get('refresh')))
        if method == 'engines/job':return ENGINES.read_job(params.get('id'))
        return ENGINES.action(params.get('target'), params.get('action'), params.get('confirmed') is True)
    if method in ('context/read', 'context/write'):
        thread_id = str(params.get('threadId', ''))
        uuid.UUID(thread_id.removeprefix('claude-').removeprefix('deepseek-'))
        directory = STATE / 'contexts'
        path = directory / (thread_id + '.txt')
        with APP.lock:
            if method == 'context/read':
                return {'context': path.read_text() if path.exists() else ''}
            context = params.get('context', '')
            if not isinstance(context, str) or len(context) > 100000:
                raise ValueError('交接上下文过长，请在项目中新建会话')
            directory.mkdir(mode=0o700, exist_ok=True)
            temp = path.with_suffix('.tmp'); temp.write_text(context); temp.replace(path)
            return {'ok': True}
    if method == 'engines/list':
        if ENGINES:
            return ENGINES.status()
        return {'data': [{'id':'codex', 'name':'Codex', 'available':True}, CLAUDE.status() if CLAUDE else {'id':'claude','name':'Claude Code','available':False,'error':'Claude 桥接尚未启动'}, DEEPSEEK.status() if DEEPSEEK else {'id':'deepseek','name':'DeepSeek','available':False,'error':'DeepSeek 桥接尚未启动'}]}
    if method.startswith('mcp/'):
        if MCP is None:raise RuntimeError('MCP 管理尚未初始化')
        if method=='mcp/config/read':return {'config':{'mcp_servers':MCP.read()}}
        if method=='mcp/config/write':return MCP.save(params.get('name'),params.get('value'))
        if method=='mcp/list':return MCP.status(engine,bool(params.get('refresh')))
        if method=='mcp/reload':return MCP.reload()
        if method=='mcp/login' and engine=='codex':return APP.call('mcpServer/oauth/login',params)
        raise ValueError('该助手需使用其原生授权方式，或在连接中填写访问令牌')
    thread_id = params.get('threadId', '')
    bridge = {'claude':CLAUDE,'deepseek':DEEPSEEK}.get(engine)
    if method=='extensions/list' and engine!='codex':
        return extensions(engine,params.get('cwd',str(Path.home())))
    if engine!='codex' and method.startswith('threadSection/'):
        path=STATE/(engine+'-sections.json')
        with APP.lock:
            sections=json.loads(path.read_text()) if path.exists() else []
            if method=='threadSection/list':return {'data':sections}
            if method=='threadSection/create':
                name=str(params.get('name','')).strip()
                if not name:raise ValueError('分组名称不能为空')
                section={'id':uuid.uuid4().hex,'name':name};sections.append(section)
                temp=path.with_suffix('.tmp');temp.write_text(json.dumps(sections,ensure_ascii=False));temp.replace(path)
                return {'section':section}
            raise ValueError('该助手尚不支持此分组操作')
    if ENGINES and (method.startswith('fs/') or method in ('command/exec','command/exec/terminate')):
        return APP.local_tools.call(method,params)
    if method=='turn/start' and PROVIDERS:
        profile=PROVIDERS.selected(engine)
        if not profile.get('key') or not profile.get('baseUrl'):raise ValueError('请先为 '+engine+' 配置官方 API 或第三方 API 地址和密钥')
    if method=='turn/start' and engine=='codex' and MCP:
        APP.start()
        MCP.sync_codex()
    if method in ('thread/start', 'thread/resume'):
        if method == 'thread/resume' and not params.get('cwd'):
            params['cwd'] = (bridge.call('thread/read', {'threadId':thread_id}) if bridge else APP.call('thread/read', {'threadId':thread_id,'includeTurns':False}))['thread']['cwd']
        params['developerInstructions'] = shared_instructions(dict(params,engine=engine))
        if PROVIDERS and engine=='codex':params['modelProvider']='ai_xs'
    if method=='model/list' and PROVIDERS and engine in ('codex','claude','deepseek'):
        return PROVIDERS.model_list(engine,APP.call(method,params) if engine=='codex' else {})
    if engine!='codex' and (method.startswith(('thread/', 'turn/')) or method in ('model/list', 'usage/read')):
        if not bridge:raise RuntimeError('助手桥接尚未启动')
        return bridge.call(method, params)
    if method == "usage/read":
        return read_thread_usage(params.get("threadId"))
    if method == "projects/list":
        with APP.lock:
            value = read_ui_state()
            return {"data": value["projects"], "workspace": value["workspace"], "standalonePaths": value.get("standalonePaths", [])}
    if method in {"projects/create", "projects/add", "workspace/update"}:
        with APP.lock:
            value = read_ui_state()
            if method == "workspace/update":
                raw_path = str(params.get("path", "")).strip()
                if not raw_path or not Path(raw_path).expanduser().is_absolute():
                    raise ValueError("工作目录必须使用绝对路径")
                folder = Path(raw_path).expanduser().resolve()
                folder.mkdir(parents=True, exist_ok=True)
                if not folder.is_dir():
                    raise ValueError("工作目录不是文件夹")
                name = str(params.get("name", "")).strip() or folder.name or "工作目录"
                previous_root = value["workspace"]["path"]
                if previous_root != str(folder):
                    value["standalonePaths"] = list(dict.fromkeys(value.get("standalonePaths", []) + [previous_root]))
                value["workspace"] = {"path": str(folder), "name": name}
                value["projects"] = [p for p in value["projects"] if p["path"] != str(folder)]
                save_ui_state(value)
                return {"workspace": value["workspace"], "data": value["projects"], "standalonePaths": value.get("standalonePaths", [])}
            if method == "projects/create":
                name = str(params.get("name", "")).strip()
                if not name or name in {".", ".."} or any(c in name for c in "/\\\0"):
                    raise ValueError("项目名称必须是单个文件夹名称，不能包含路径分隔符")
                workspace = Path(value["workspace"]["path"]).resolve()
                workspace.mkdir(parents=True, exist_ok=True)
                folder = workspace / name
                folder.mkdir(exist_ok=False)
            else:
                raw_path = str(params.get("path", "")).strip()
                if not raw_path or not Path(raw_path).expanduser().is_absolute():
                    raise ValueError("项目文件夹必须使用绝对路径")
                folder = Path(raw_path).expanduser().resolve()
                if not folder.is_dir():
                    raise ValueError("项目文件夹不存在")
                name = str(params.get("name", "")).strip() or folder.name
            if str(folder) == value["workspace"]["path"]:
                raise ValueError("总工作目录不能同时作为项目，请选择其中的项目文件夹")
            project = {"path": str(folder), "name": name}
            value["standalonePaths"] = [path for path in value.get("standalonePaths", []) if path != str(folder)]
            value["projects"] = [p for p in value["projects"] if p["path"] != str(folder)] + [project]
            save_ui_state(value)
            return {"project": project, "data": value["projects"], "standalonePaths": value.get("standalonePaths", [])}
    if method == "events/poll":
        cursor = int(params.get("cursor", 0))
        wait = min(max(float(params.get("wait", 0)), 0), 18)
        with APP.event_condition:
            if cursor >= APP.event_id and wait:
                APP.event_condition.wait(timeout=wait)
            events = [e for e in APP.events if e["seq"] > cursor][:100]
            return {"data": events, "cursor": events[-1]["seq"] if events else APP.event_id,
                    "approvals": list(APP.approvals.values()),
                    "gap": bool(APP.events and cursor and cursor < APP.events[0]["seq"] - 1)}
    if method == "files/upload":
        name = Path(params.get("name", "image")).name
        raw = base64.b64decode(params.get("data", ""), validate=True)
        if len(raw) > 15 * 1024 * 1024:
            raise ValueError("单张图片不能超过 15 MB")
        if not (raw.startswith(b'\x89PNG\r\n\x1a\n') or raw.startswith(b'\xff\xd8\xff') or raw.startswith((b'GIF87a', b'GIF89a')) or (raw.startswith(b'RIFF') and raw[8:12] == b'WEBP')):
            raise ValueError("请使用 PNG、JPEG、GIF 或 WebP 图片")
        target = UPLOADS / (uuid.uuid4().hex + "-" + name)
        target.write_bytes(raw)
        return {"path": str(target), "name": name, "mimeType": params.get("mimeType", "application/octet-stream")}
    if method == "approvals/respond":
        if str(params['id']).startswith('deepseek:') and DEEPSEEK:
            DEEPSEEK.respond(params['id'], params.get('result', {}))
        elif str(params['id']).startswith('claude:') and CLAUDE:
            CLAUDE.respond(params['id'], params.get('result', {}))
        else:
            APP.respond(params["id"], params.get("result", {}))
        return {"ok": True}
    if method == "command/exec":
        params = dict(params)
        params.setdefault("processId", uuid.uuid4().hex)
        return APP.call("command/exec", params, timeout=300)
    if method == "health":
        return {"userAgent": APP.user_agent, "codexHome": str(Path.home() / ".codex"), "eventCursor": APP.event_id}
    if method == "state/read":
        with APP.lock:
            return read_ui_state()
    if method == "state/write":
        with APP.lock:
            value = read_ui_state()
            # Workspace/project registration is owned by dedicated validated APIs.
            for key in ["cwd", "scope", "model", "effort", "settings", "notes", "pins", "lastThreadId", "modelPrefs", "modelChanges", "queuedMessages", "engine", "enginePrefs", "lastThreadByEngine", "projectRequirements", "handoffContexts", "handoffDraft"]:
                if key in params:
                    value[key] = params[key]
            save_ui_state(value)
        return {"ok": True}
    if not method.startswith(("thread/", "threadSection/", "turn/", "model/", "skills/", "plugin/", "marketplace/", "fs/", "review/", "config/", "account/", "mcpServer/", "mcpServerStatus/", "experimentalFeature/", "permissionProfile/", "hooks/", "app/", "fuzzyFileSearch", "command/exec/", "externalAgentConfig/")):
        raise ValueError("不支持的接口")
    return APP.call(method, params, timeout=300)
