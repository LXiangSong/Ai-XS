"""Private stdin/stdout Agent service for Android native screens. No HTTP server."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import signal
import sqlite3
import sys
import threading

import agent_core as core
from shared_conversations import SharedConversations
from extension_manager import ExtensionManager


class NativeHost:
    def __init__(self, output=None):
        self.output=output or sys.stdout
        self.lock=threading.Lock()
        self.slots=threading.BoundedSemaphore(12)
        self.pool=ThreadPoolExecutor(max_workers=4,thread_name_prefix='aixs-request')
        self.control_slots=threading.BoundedSemaphore(4)
        self.controls=ThreadPoolExecutor(max_workers=2,thread_name_prefix='aixs-control')
        self.chats=None

    def write(self, value):
        with self.lock:
            self.output.write(json.dumps(value,ensure_ascii=False)+'\n')
            self.output.flush()

    def initialize(self):
        core.APP=core.AppServer()
        original=core.APP._emit
        def emit(event):
            original(event)
            if self.chats:
                try:event=self.chats.translate(event,persist=True)
                except Exception as error:
                    print('共享会话记录失败：'+str(error),file=sys.stderr,flush=True)
                    event=self.chats.translate(event)
            self.write({'event':event})
        core.APP._emit=emit
        core.APP.local_tools.emit=emit
        core.CLAUDE=core.ClaudeBridge(core.APP,core.STATE/'claude')
        core.DEEPSEEK=core.DeepSeekBridge(core.APP,core.STATE/'deepseek')
        core.PROVIDERS=core.ProviderManager(core.STATE)
        core.MCP=core.McpManager(core.APP,core.STATE)
        self.extensions=ExtensionManager(core.STATE)
        core.EXTENSIONS=self.extensions
        core.MCP.extensions=self.extensions
        for bridge in [core.APP,core.CLAUDE,core.DEEPSEEK]:
            bridge.providers=core.PROVIDERS;bridge.mcp=core.MCP
            bridge.extensions=self.extensions
        core.ENGINES=core.EngineManager(core.APP,core.CLAUDE,core.STATE,core.DEEPSEEK)
        self.chats=SharedConversations(core.STATE/'conversations',core.api_route,
            self.read_thread,self.read_usage,core.PERMISSIONS.mode)
        core.ENGINES.boot()
        self.maintenance=False
        self.maintenance_lock=threading.RLock()
        self.starting_tasks=0
        self.write({'ready':True,'name':'Ai-XS','transport':'private-stdio','version':'0.8.6-alpha'})

    def dispatch(self, request, slot=None):
        ident=request.get('id')
        starting=False
        try:
            method=request['method'];params=request.get('params',{})
            if not isinstance(method,str) or not isinstance(params,dict):raise ValueError('请求参数格式无效')
            if method in ('native/chat/turn/start','native/chat/turn/edit','turn/start','turn/edit'):
                with self.maintenance_lock:
                    if self.maintenance:raise ValueError('正在更新 Agent，请等待完成后再发送任务')
                    self.starting_tasks+=1;starting=True
            if method=='native/maintenance/prepare':
                with self.maintenance_lock,self.chats.lock:
                    if self.starting_tasks or self.chats.busy or core.APP.active_turns or core.CLAUDE.runs or core.DEEPSEEK.runs:
                        raise ValueError('仍有 Agent 任务在运行，请先结束任务再更新')
                    self.maintenance=True
                result={'ready':True}
            elif method.startswith('native/extensions/'):
                action=method.removeprefix('native/extensions/')
                if action=='list':
                    result=self.extensions.listing()
                    result['external']=[core.extensions(e,params.get('cwd',str(Path.home()))) for e in ('codex','claude','deepseek')]
                elif action=='preview':result=self.extensions.preview(params)
                elif action=='import':result=self.extensions.import_local(params)
                elif action=='install':result=self.extensions.install(params)
                elif action=='configure':result=self.extensions.configure(params)
                elif action=='remove':result=self.extensions.remove(params.get('id'))
                else:raise ValueError('不支持的扩展操作')
            elif method.startswith('native/chat/'):
                action=method.removeprefix('native/chat/')
                if action.startswith('permissions/'):
                    result=self.chats.permissions(action,params) if params.get('threadId') else core.api_route('native/'+action,params)
                elif action=='usage/read':result=self.chats.usage(params)
                else:result=self.chats.handle(action,params)
            elif method=='native/check':
                result=self.check()
            elif method=='native/createFile':
                path=Path(params.get('path',''))
                if not path.is_absolute():raise ValueError('请使用绝对路径')
                with path.open('x'):pass
                result={}
            elif method=='model/list':
                result=core.PROVIDERS.model_list(params.get('engine','codex'),{})
            elif method=='native/thread/read':
                result=self.read_thread(params)
            elif method=='native/threads/list':
                result=self.list_threads(params)
            elif method=='native/usage/read':
                result=self.read_usage(params)
            elif method=='native/status':
                result={'name':'Ai-XS','transport':'private-stdio','eventCursor':core.APP.event_id,
                    'approvals':[self.chats.translate(value) for value in core.APP.approvals.values()]}
            else:
                result=core.api_route(method,params)
            self.write({'id':ident,'result':result})
        except Exception as error:
            # Diagnostics in the Android host redacts known secrets before display.
            self.write({'id':ident,'error':str(error)})
        finally:
            if starting:
                with self.maintenance_lock:self.starting_tasks-=1
            (slot or self.slots).release()

    def read_usage(self, params):
        if core.engine_for(params)=='codex':
            record=self.codex_record(str(params.get('threadId') or ''))
            return core.read_thread_usage(params.get('threadId'),{'path':record.get('rollout_path')} if record else {})
        return core.api_route('usage/read',params)

    def list_threads(self, params):
        """Merge local histories without activating three Agent runtimes."""
        values=[];errors=[]
        for engine in ('codex','claude','deepseek'):
            try:
                if engine=='codex':
                    rows=self.codex_threads(bool(params.get('archived')))
                else:
                    bridge={'claude':core.CLAUDE,'deepseek':core.DEEPSEEK}[engine]
                    rows=bridge.call('thread/list',{'archived':bool(params.get('archived')),'nativeDiscovery':'background'})['data']
                for row in rows:
                    if not row.get('id'):continue
                    values.append(dict(row,engine=engine))
            except Exception as error:
                errors.append({'engine':engine,'error':str(error)})
        if self.chats:values=self.chats.listing(values,bool(params.get('archived')))
        cwd=params.get('cwd');needle=str(params.get('searchTerm') or '').casefold()
        values=[row for row in values
                if (not cwd or row.get('cwd') in (cwd if isinstance(cwd,list) else [cwd]))
                and needle in (str(row.get('name') or '')+' '+str(row.get('preview') or '')).casefold()]
        def timestamp(row):
            try:return float(row.get('updatedAt') or row.get('createdAt') or 0)
            except (ValueError,TypeError):return 0
        values.sort(key=lambda row:(timestamp(row),row['id']),reverse=True)
        limit=max(1,min(500,int(params.get('limit',100))))
        offset=max(0,int(params.get('cursor') or 0))
        return {'data':values[offset:offset+limit],'total':len(values),'errors':errors,
                'nextCursor':str(offset+limit) if offset+limit<len(values) else None}

    @staticmethod
    def codex_database():
        home=Path(os.environ.get('CODEX_HOME') or Path.home()/'.codex')
        databases=[p for p in home.glob('state_*.sqlite') if p.stem.removeprefix('state_').isdigit()]
        return max(databases,key=lambda p:int(p.stem.removeprefix('state_'))) if databases else None

    @classmethod
    def codex_record(cls, thread_id):
        database=cls.codex_database()
        if database is None:return None
        connection=sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True,timeout=2)
        try:
            connection.row_factory=sqlite3.Row
            row=connection.execute('SELECT * FROM threads WHERE id=?',(thread_id,)).fetchone()
            return dict(row) if row else None
        finally:connection.close()

    @classmethod
    def codex_threads(cls, archived):
        # Codex's own index is read-only here. Merely opening the drawer must not
        # launch app-server or interrupt an active Claude/DeepSeek task.
        database=cls.codex_database()
        if database is None:
            process=getattr(core.APP,'process',None)
            if process and process.poll() is None:
                values=[];cursor=None
                while True:
                    page=core.APP.call('thread/list',{'limit':100,'archived':archived,**({'cursor':cursor} if cursor else {})})
                    values.extend(page.get('data',[]));cursor=page.get('nextCursor')
                    if not cursor:return values
            return []
        connection=sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True,timeout=2)
        try:
            connection.row_factory=sqlite3.Row
            rows=connection.execute('SELECT * FROM threads WHERE archived=?',(int(archived),)).fetchall()
            result=[]
            for entry in rows:
                row=dict(entry)
                result.append({'id':row['id'],'cwd':row.get('cwd') or '',
                    'name':row.get('name') or row.get('title') or '',
                    'preview':row.get('preview') or row.get('first_user_message') or row.get('title') or '',
                    'model':row.get('model') or '',
                    'createdAt':row.get('created_at') or 0,'updatedAt':row.get('updated_at') or 0,
                    'archived':bool(row.get('archived'))})
            return result
        finally:connection.close()

    def read_thread(self, params):
        thread_id=str(params.get('threadId',''))
        try:
            result=core.api_route('thread/read',dict(params,includeTurns=False))
        except Exception as error:
            # Idle Codex is stopped when another Agent runs. Restore by local ID,
            # independent of the currently configured API provider's list filter.
            if 'thread not loaded' not in str(error).lower() or thread_id.startswith(('claude-','deepseek-')):
                raise
            record=self.codex_record(thread_id)
            if not record:
                for archived in (False,True):
                    cursor=None
                    while True:
                        listing=core.api_route('thread/list',{'limit':100,'archived':archived,
                            'modelProviders':[],**({'cursor':cursor} if cursor else {})})
                        record=next((item for item in listing.get('data',[]) if item.get('id')==thread_id),None)
                        cursor=listing.get('nextCursor')
                        if record or not cursor:break
                    if record:break
            try:
                # Even an unindexed rollout may still be resumable. Do not clear
                # the selection just because it is absent from a filtered list.
                core.api_route('thread/resume',{'threadId':thread_id,
                    'cwd':(record or {}).get('cwd') or params.get('cwd') or str(Path.home())})
            except Exception as resume_error:
                missing=any(text in str(resume_error).lower() for text in (
                    'no rollout found','thread not found','session not found','unknown thread',
                    'thread does not exist','session does not exist','not found for thread'))
                if not record and missing:
                    return {'thread':None,'threadMissing':True,'missingThreadId':thread_id}
                if 'already' not in str(resume_error).lower():
                    raise RuntimeError('Codex 会话恢复失败：'+str(resume_error)) from resume_error
            try:
                result=core.api_route('thread/read',dict(params,includeTurns=False))
            except Exception as retry_error:
                raise RuntimeError('Codex 会话无法加载：'+str(retry_error)) from retry_error
        turns=core.api_route('thread/turns/list',dict(params,limit=100,sortDirection='desc',itemsView='full'))
        result['thread']['turns']=list(reversed(turns.get('data',[])))
        result['olderTurnsCursor']=turns.get('nextCursor')
        return result

    def check(self):
        engines=core.api_route('engines/list',{})['data'];checked=[]
        for item in engines:
            if not item.get('available'):continue
            engine=item['id'];params={'engine':engine}
            models=core.api_route('model/list',params)['data']
            profile=core.api_route('provider/read',params)
            core.api_route('threadSection/list',params)
            servers=core.api_route('mcp/list',dict(params,refresh=True))['data']
            device=next((s for s in servers if s['name']=='aixs-device'),{})
            if not device.get('tools'):raise RuntimeError(engine+' 未连接内置手机工具')
            checked.append({'agent':engine,'version':item.get('version'),'models':len(models),
                'apiMode':profile['mode'],'keyConfigured':profile['profiles'][profile['mode']]['keyConfigured'],
                'tools':len(device['tools'])})
        return {'data':checked,'detail':'原生进程通信检查通过；未发送推理请求。'}

    def run(self, source=None):
        self.initialize()
        try:
            for raw in source or sys.stdin:
                try:
                    if len(raw)>24*1024*1024:raise ValueError('请求过大')
                    request=json.loads(raw)
                    if not isinstance(request,dict) or 'id' not in request:raise ValueError('请求缺少标识')
                    control=request.get('method') in ('native/chat/turn/interrupt','native/chat/turn/status','turn/interrupt','approvals/respond','command/exec/terminate')
                    slots=self.control_slots if control else self.slots
                    pool=self.controls if control else self.pool
                    if not slots.acquire(blocking=False):
                        self.write({'id':request['id'],'error':'已有多个操作正在执行，请稍后重试'});continue
                    pool.submit(self.dispatch,request,slots)
                except (ValueError,TypeError) as error:self.write({'id':None,'error':str(error)})
        finally:
            core.ENGINES.closing=True
            core.APP.local_tools.close();core.CLAUDE.close();core.DEEPSEEK.close();core.APP.stop()
            self.pool.shutdown(wait=False,cancel_futures=True)
            self.controls.shutdown(wait=False,cancel_futures=True)


if __name__=='__main__':
    def stop(*args):raise KeyboardInterrupt
    signal.signal(signal.SIGTERM,stop)
    try:NativeHost().run()
    except KeyboardInterrupt:pass
