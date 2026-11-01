"""Private stdin/stdout Agent service for Android native screens. No HTTP server."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import signal
import sqlite3
import sys
import threading
import time

import agent_core as core
from shared_conversations import SharedConversations
from extension_manager import ExtensionManager
from collaboration_service import CollaborationService
from native_history import remove_owned


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
            if getattr(self,'collaboration',None):self.collaboration.normal_event(event)
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
        self.collaboration=CollaborationService(core.STATE/'collaboration',self.chats,core.PROVIDERS,core.MCP,
            self.extensions,core.ENGINES,lambda event:self.write({'event':event}),self.external_tasks)
        self.maintenance=False
        self.maintenance_lock=threading.RLock()
        self.starting_tasks=0
        version=json.loads(Path(__file__).with_name('version.json').read_text())
        self.write({'ready':True,'name':'Ai-XS','transport':'private-stdio','version':version['versionName']})
        threading.Thread(target=self.purge_deleted,name='aixs-purge-deleted',daemon=True).start()

    def purge_deleted(self):
        try:
            result=self.collaboration.purge_deleted(self.delete_conversation_data)
        except Exception as error:
            print('清理已删除会话失败：'+str(error),file=sys.stderr,flush=True)
            return
        if result.get('purged') or result.get('failed'):
            print('已删除会话清理：完成 %s 个，待重试 %s 个'%(result.get('purged',0),result.get('failed',0)),
                file=sys.stderr,flush=True)
            self.write({'event':{'type':'native/cleanup','purged':result.get('purged',0),
                'failed':result.get('failed',0)}})
        for item in result.get('errors',[]):
            print('清理失败 %s：%s'%(item.get('threadId',''),item.get('error','')),file=sys.stderr,flush=True)


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
                with self.maintenance_lock,self.collaboration.lock,self.chats.lock:
                    if self.starting_tasks or self.chats.busy or core.APP.active_turns or core.CLAUDE.runs or core.DEEPSEEK.runs or self.collaboration.active():
                        raise ValueError('仍有 Agent 任务在运行，请先结束任务再更新')
                    self.collaboration.scheduler.closed=True
                    self.maintenance=True
                result={'ready':True}
            elif method.startswith('native/collaboration/'):
                with self.maintenance_lock:
                    if self.maintenance and method.rsplit('/',1)[-1] not in ('read','list','resources','pause','cancel','stop','respond'):
                        raise ValueError('正在维护 Agent，请稍后重试')
                    result=self.collaboration.handle(method.removeprefix('native/collaboration/'),params)
                    if method=='native/collaboration/resources':
                        with self.chats.lock:
                            result['activeTurnKeys']=[d['id']+':'+t['id'] for key,row in list(self.chats.index.items()) if not row.get('deleted')
                                for d in [self.chats._load(key)] for t in d['turns'] if t.get('status')=='inProgress' and not t.get('queued')]
            elif method=='native/agents/status':
                result=core.ENGINES.status(probe=False)
            elif method=='native/agents/control':
                result=self.control_agents(params)
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
                if action.startswith('queue/'):
                    with self.maintenance_lock:
                        if self.maintenance and action not in ('queue/list','queue/delete','queue/pause'):raise ValueError('正在维护 Agent，请稍后重试')
                        result=self.collaboration.queue.handle(action.removeprefix('queue/'),params)
                elif action.startswith('execution/'):
                    with self.maintenance_lock:
                        if self.maintenance and action!='execution/read':raise ValueError('正在维护 Agent，请稍后重试')
                        result=self.collaboration.execution.handle(action.removeprefix('execution/'),params)
                elif action.startswith('permissions/'):
                    result=self.chats.permissions(action,params) if params.get('threadId') else core.api_route('native/'+action,params)
                elif action=='usage/read':result=self.chats.usage(params)
                elif action=='thread/delete':result=self.collaboration.delete_conversation(params,self.delete_conversation_data)
                elif action in ('turn/start','turn/edit'):result=self.collaboration.normal_submit(action,params)
                elif action=='turn/interrupt' and self.collaboration.normal_cancel(params):result={}
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
            elif method in ('turn/start','turn/edit'):
                # Legacy adapter clients cannot bypass admission while shared
                # collaboration workers hold execution or project resources.
                with self.collaboration.lock:
                    if self.collaboration.active() or self.collaboration.scheduler.leases:
                        raise ValueError('协作正在使用执行资源，请通过共享会话入口提交任务并排队')
                    result=core.api_route(method,params)
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

    def control_agents(self, params):
        action=params.get('action');target=params.get('target')
        if action not in ('start','stop'):raise ValueError('不支持的 Agent 操作')
        if target not in ('all','codex','claude','deepseek'):raise ValueError('未知的 Agent')
        if action=='stop' and params.get('confirmed') is not True:raise ValueError('请确认停止 Agent，运行中的任务将被中断')
        requested=params.get('targets') if target=='all' else [target]
        if requested is None:requested=['codex','claude','deepseek']
        if not isinstance(requested,list) or not requested or any(e not in ('codex','claude','deepseek') for e in requested):
            raise ValueError('Agent 列表无效')
        results=[]
        # Serialize with turn submission and environment maintenance. Existing
        # work in unrelated Agents remains untouched by a targeted stop.
        with self.maintenance_lock:
            if self.maintenance:raise ValueError('正在维护 Agent，请稍后重试')
            if self.starting_tasks:raise ValueError('正在提交任务，请稍后重试启停操作')
            if action=='stop':
                with self.collaboration.lock:self.collaboration.stop_engine(None if target=='all' else target)
                deadline=time.monotonic()+35
                while self.collaboration.active(None if target=='all' else target):
                    if time.monotonic()>deadline:raise ValueError('协作执行尚未确认退出，请查看协作任务，暂不停止共享服务')
                    time.sleep(.1)
            for engine in dict.fromkeys(requested):
                try:
                    job=core.ENGINES.action(engine,action,confirmed=params.get('confirmed') is True)['job']
                    deadline=time.monotonic()+45
                    while job['status'] in ('pending','running'):
                        if time.monotonic()>deadline:raise TimeoutError('Agent 启停超时，请查看运行日志')
                        time.sleep(.025)
                        job=core.ENGINES.read_job(job['id'])['job']
                    results.append({'agent':engine,'status':job['status'],'message':job.get('error') or
                        ('已启动，空闲就绪；任务按需运行' if action=='start' else '已停止，项目与会话已保留'),'log':job.get('log',[])})
                except Exception as error:
                    results.append({'agent':engine,'status':'failed','message':str(error)})
        return {'data':results,'ok':all(r['status']=='completed' for r in results)}

    def external_tasks(self):
        with self.chats.lock:
            docs=[self.chats._load(key) for key,row in list(self.chats.index.items()) if not row.get('deleted')]
            docs=[d for d in docs if any(t.get('status')=='inProgress' and not t.get('queued') for t in d['turns']) and d['id'] not in getattr(getattr(self,'collaboration',None),'normal_active',set())]
            return {'count':len(docs),'projects':[d['cwd'] for d in docs]}

    def delete_conversation_data(self, doc):
        def images(value):
            result=set()
            if isinstance(value,list):
                for item in value:result.update(images(item))
            elif isinstance(value,dict):
                if value.get('type') in ('localImage','image'):
                    path=value.get('path') or value.get('url')
                    if isinstance(path,str) and Path(path).is_absolute():result.add(path)
                for item in value.values():
                    if isinstance(item,(dict,list)):result.update(images(item))
            return result
        with self.chats.lock:
            others=[self.chats._load(key) for key,row in list(self.chats.index.items()) if key!=doc['id'] and not row.get('deleted')]
            retained=set().union(*(images(d) for d in others))
        for name in images(doc)-retained:
            path=Path(name)
            if path.parent.resolve()==core.UPLOADS.resolve():remove_owned(core.UPLOADS,path)
        ids={doc['id'],*(c['threadId'] for c in doc['children'])}
        for native_id in ids:core.APP.codex_children.forget(native_id)
        with core.APP.event_condition:
            keep=[e for e in core.APP.events if e.get('params',{}).get('threadId') not in ids]
            core.APP.events.clear();core.APP.events.extend(keep)

    def read_usage(self, params):
        if core.engine_for(params)=='codex':
            record=self.codex_record(str(params.get('threadId') or ''))
            value=core.read_thread_usage(params.get('threadId'),{'path':record.get('rollout_path')} if record else {})
            if value.get('tokenUsage') is not None:value['tokenUsage']=core.APP.codex_children.merge_usage(params.get('threadId'),value['tokenUsage'])
            return value
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
                    control=request.get('method') in ('native/collaboration/stop','native/collaboration/pause','native/collaboration/cancel','native/collaboration/respond','native/collaboration/resources','native/chat/turn/interrupt','native/chat/turn/status','turn/interrupt','approvals/respond','command/exec/terminate')
                    slots=self.control_slots if control else self.slots
                    pool=self.controls if control else self.pool
                    if not slots.acquire(blocking=False):
                        self.write({'id':request['id'],'error':'已有多个操作正在执行，请稍后重试'});continue
                    pool.submit(self.dispatch,request,slots)
                except (ValueError,TypeError) as error:self.write({'id':None,'error':str(error)})
        finally:
            self.collaboration.close()
            core.ENGINES.closing=True
            core.APP.local_tools.close();core.CLAUDE.close();core.DEEPSEEK.close();core.APP.stop()
            self.pool.shutdown(wait=False,cancel_futures=True)
            self.controls.shutdown(wait=False,cancel_futures=True)


if __name__=='__main__':
    def stop(*args):raise KeyboardInterrupt
    signal.signal(signal.SIGTERM,stop)
    try:NativeHost().run()
    except KeyboardInterrupt:pass
