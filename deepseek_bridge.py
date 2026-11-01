"""Adapt the installed DeepSeek Harness ACP protocol to Ai-XS sessions."""
from __future__ import annotations
import base64
import copy
import json
import os
import shutil
from pathlib import Path
import threading
import time
import uuid
from permission_modes import validate as validate_permission
from claude_bridge import ClaudeBridge
from rpc_process import RpcProcess
from deepseek_usage import SessionUsage
from conversation_usage import add, execution_stats
from realtime_events import apply as apply_live, settle


class DeepSeekBridge(ClaudeBridge):
    engine = 'deepseek'
    label = 'DeepSeek'

    def __init__(self, app, folder):
        self.usage_readers={}
        self.child_context=threading.local()
        super().__init__(app,folder)

    def _notify(self, method, params):
        context=getattr(self.child_context,'value',None)
        if context:
            parent,session=context;params=copy.deepcopy(params)
            prefix='dsh-child:'+session+':'+str(parent.get('children',{}).get(session,{}).get('nativeTurnId') or '')+':'
            meta={'nativeChild':True,'sourceEngine':'deepseek','sourceThreadId':session,
                  'sourceTurnId':parent.get('children',{}).get(session,{}).get('nativeTurnId'),
                  'sourceModel':parent.get('childModels',{}).get(session),'parentToolUseId':'dsh-child:'+session}
            params.update(meta)
            if params.get('itemId'):params['itemId']=prefix+params['itemId']
            if params.get('item'):
                params['item'].update(meta);params['item']['id']=prefix+params['item']['id']
                if params['item'].get('type')=='agentMessage':params['item']['type']='subagentMessage'
            if method in ('turn/plan/updated','turn/diff/updated','error'):
                scratch={'items':[]};apply_live(scratch,method,params)
                for item in scratch['items']:
                    self._notify('item/started' if item.get('status')=='inProgress' else 'item/completed',
                        {'threadId':parent['data']['id'],'turnId':parent['turn']['id'],'item':item})
                return
            if method=='native/turnStatus':
                progress=params.get('progress',{})
                params['item']={'id':prefix+'status','type':'runtimeStatus','tool':'DeepSeek 子任务',
                    'status':'inProgress','progressMessage':progress.get('detail') or progress.get('phase',''),**meta}
                method='item/started'
            apply_live(parent['turn'],method,params)
        super()._notify(method,params)

    def _child_message(self, run, message):
        params=message.get('params') or {};session=params.get('sessionId');ancestors=params.get('ancestors') or []
        if session==run.get('sessionId') or run.get('sessionId') not in ancestors:return False
        if not isinstance(session,str) or len(ancestors)>32:return True
        if session not in run.setdefault('children',{}):
            run['children'][session]={'data':run['data'],'turn':{'id':run['turn']['id'],'items':[]},
                'sessionId':session,'rootRun':run,'requests':run.setdefault('requests',{})}
            run['data']['nativeSessions']=list(dict.fromkeys(run['data'].get('nativeSessions',[])+[session]))
        child=run['children'][session];method=message.get('method')
        if method=='aixs/childModel':
            run.setdefault('childModels',{})[session]=params.get('model');return True
        old=getattr(self.child_context,'value',None);self.child_context.value=(run,session)
        try:
            if method=='aixs/childTask':
                active=run.setdefault('activeChildTasks',set());key=params.get('id')
                if params.get('status')=='inProgress':active.add(key)
                else:active.discard(key)
                self._item(child,{'id':'activation:'+str(key),'type':'taskProgress','tool':'DeepSeek 子任务','status':params.get('status','inProgress')})
            elif method=='aixs/childLifecycle':
                status='inProgress'
                native_turn=str(params.get('turn'))
                if params.get('type')=='turn/start' and child.get('nativeTurnId')!=native_turn:
                    child['turn']={'id':run['turn']['id'],'items':[],'startedAt':time.time(),'status':'inProgress'}
                    child['nativeTurnId']=native_turn
                if params.get('type')=='turn/end':
                    reason=params.get('reason');reason=reason.get('kind') if isinstance(reason,dict) else reason
                    status='failed' if reason in ('error','failed','blocked','max-tokens') else 'interrupted' if reason in ('cancelled','canceled','interrupted','aborted','forked') else 'completed'
                    child['turn'].update(status=status,completedAt=time.time())
                    settle(child['turn'],status)
                    for item in child['turn']['items']:self._item(child,item)
                self._item(child,{'id':'lifecycle:'+str(params.get('turn')),'type':'taskProgress','tool':'DeepSeek 子任务','status':status})
                if status!='inProgress':self._item(child,{'id':'status','type':'runtimeStatus','tool':'DeepSeek 子任务','status':status})
                if status!='inProgress':self._notify('native/childTurn',{'threadId':run['data']['id'],'turnId':run['turn']['id'],
                    'childTurn':copy.deepcopy(child['turn']),'stats':execution_stats(child['turn']['items'],status,child['turn'].get('startedAt'),child['turn'].get('completedAt'))})
            else:
                routed=copy.deepcopy(message)
                if method=='aixs/childUpdate':routed['method']='session/update'
                self._message(child,routed)
        finally:self.child_context.value=old
        return True

    def _read_usage(self, params):
        with self.lock:
            tid=params.get('threadId');run=self.runs.get(tid)
            if run:data=run['data']
            else:
                if not self._path(tid).exists():super().call('usage/read',params)  # Adopt native-only sessions first.
                data=self._read(tid)
            self._refresh_usage(data)
            return {'threadId':tid,'model':data.get('usageModel',data.get('model')),'tokenUsage':copy.deepcopy(data.get('tokenUsage')),
                'recordedAt':data.get('usageAt'),'contextStale':bool(data.get('contextStale')),
                'totalsPartial':bool(data.get('usagePartial')),'usageError':data.get('usageError'),
                'nativeUsageRecovered':bool(data.get('nativeUsageSnapshots')) and not data.get('usagePartial')}

    def _refresh_usage(self, data, notify=False):
        sources=data.get('nativeUsageSnapshots') or {};changed=False
        ids=list(dict.fromkeys(data.get('nativeSessions',[])+[data.get('nativeSession')]))
        for session in ids:
            if not session:continue
            path=self._dsh_session_path(session)
            if not path:continue
            try:
                stat=path.stat();fingerprint=[stat.st_dev,stat.st_ino,stat.st_size,stat.st_mtime_ns]
                if sources.get(session,{}).get('nativeFile')==fingerprint and not sources[session].get('totalsPartial'):continue
                reader=self.usage_readers.get(str(path))
                if reader is None:
                    reader=SessionUsage(path,session);self.usage_readers[str(path)]=reader
                    while len(self.usage_readers)>8:self.usage_readers.pop(next(iter(self.usage_readers)))
                value=reader.read()
                value['nativeFile']=fingerprint
                if value['tokenUsage']['total'] or value.get('totalsPartial'):
                    if sources.get(session)!=value:sources[session]=value;changed=True
                data.pop('usageError',None)
            except (OSError,ValueError,RuntimeError) as error:
                self.usage_readers.pop(str(path),None)
                data['usageError']='DeepSeek 原生用量读取失败：'+str(error)
        if not sources:return
        groups={};partial=False
        for value in sources.values():
            partial|=bool(value.get('totalsPartial'))
            for entry in value['tokenUsage'].get('byModel',[]):
                model=entry.get('model');group=groups.setdefault(model or '',{'model':model,'total':{}})
                add(group['total'],entry['total'])
                if entry.get('updatedAt',0)>=group.get('updatedAt',0):
                    for key in ('last','modelContextWindow','contextStale','recordedAt','updatedAt'):
                        if key in entry:group[key]=copy.deepcopy(entry[key])
        total={}
        for group in groups.values():add(total,group['total'])
        current=sources.get(data.get('nativeSession'))
        usage=copy.deepcopy(data.get('tokenUsage') or {})
        if current:
            usage.update({k:copy.deepcopy(v) for k,v in current['tokenUsage'].items() if k in ('last','modelContextWindow')})
            data['usageModel']=current.get('model');data['contextStale']=current.get('contextStale',False)
            data['usageAt']=current.get('recordedAt')
        usage.update(total=total,byModel=list(groups.values()))
        if usage!=data.get('tokenUsage'):changed=True
        data.update(tokenUsage=usage,nativeUsageSnapshots=sources,usagePartial=partial)
        if changed:
            self._save(data)
            if notify:self._notify('thread/tokenUsage/updated',{'threadId':data['id'],'tokenUsage':copy.deepcopy(usage),
                'model':data.get('usageModel'),'totalsPartial':partial,'contextStale':bool(data.get('contextStale'))})

    def _scan_native_sessions(self):
        """List persisted ACP sessions through the CLI's own session/list API."""
        root = Path(os.environ.get('DSH_HOME') or Path.home()/'.dsh')/'sessions'
        if not root.is_dir():
            return []
        rpc = None
        entries = []
        try:
            rpc = RpcProcess(self.command(str(Path.home())) + ['--profile', 'acp'], str(Path.home()), None)
            rpc.request('initialize', {'protocolVersion': 1, 'clientCapabilities': {},
                                       'clientInfo': {'name': 'ai-xs', 'version': '1.0'}}, timeout=30)
            cursor = None
            while True:
                page = rpc.request('session/list', {'cursor': cursor} if cursor else {}, timeout=30)
                entries.extend(page.get('sessions') or [])
                cursor = page.get('nextCursor')
                if not cursor:
                    break
        except Exception:
            return []
        finally:
            if rpc:
                try:
                    rpc.close()
                except Exception:
                    pass
        values = []
        for entry in entries:
            session_id = str(entry.get('sessionId') or '')
            if not session_id:
                continue
            path = self._dsh_session_path(session_id)
            updated = path.stat().st_mtime if path else time.time()
            values.append({'nativeSession': session_id, 'cwd': entry.get('cwd') or '',
                           'name': 'DeepSeek 原生会话 ' + session_id[-6:],
                           'preview': entry.get('cwd') or '', 'model': '',
                           'createdAt': updated, 'updatedAt': updated,
                           'source': 'native', 'path': str(path) if path else ''})
        return values

    @staticmethod
    def _dsh_session_path(session_id):
        if not isinstance(session_id,str) or not session_id or Path(session_id).name!=session_id or session_id in ('.','..'):return None
        try:
            root=Path(os.environ.get('DSH_HOME') or Path.home()/'.dsh')/'sessions'
            for project in root.glob('*'):
                folder=project/session_id
                if not folder.resolve().is_relative_to(root.resolve()):continue
                for name in ('session.v4.jsonl.zstd', 'session.v4.jsonl','session.v3.jsonl.zstd','session.v3.jsonl','session.v2.jsonl.zstd','session.v2.jsonl','session.v1.jsonl.zstd','session.v1.jsonl'):
                    candidate = folder / name
                    if candidate.is_file():
                        return candidate
        except OSError:
            pass
        return None

    def _import_native(self, native):
        updated = native.get('updatedAt') or time.time()
        return {'id': self._native_thread_id(native['nativeSession']),
                'nativeSession': native['nativeSession'], 'nativeStarted': True,
                'engine': self.engine, 'cwd': native.get('cwd') or str(Path.home()),
                'name': native.get('name') or 'DeepSeek 原生会话',
                'preview': native.get('preview', ''), 'model': 'default', 'turns': [],
                'createdAt': native.get('createdAt') or updated, 'updatedAt': updated,
                'archived': False, 'permissionMode': 'ask', 'importedFromNative': True,
                'nativeNotice': '该会话由 DeepSeek 原生创建；可以继续对话，但 ACP 不会回放旧消息。'}

    def command(self, cwd):
        explicit = os.environ.get('AI_XS_DEEPSEEK_COMMAND')
        if explicit:
            value = json.loads(explicit)
            if not isinstance(value,list) or not value or not all(isinstance(x,str) for x in value):
                raise ValueError('AI_XS_DEEPSEEK_COMMAND 必须是 JSON 命令数组')
            if not shutil.which(value[0]):
                raise RuntimeError('DeepSeek 尚未安装或启动程序已失效，请到设置 → Agent 管理 → DeepSeek 安装或重新导入部署包')
            return value
        binary=shutil.which('dsh')
        if not binary:raise RuntimeError('未找到 DeepSeek，请在设置 → Agent 管理中安装')
        return [binary]

    def _connect(self, cwd, callback=None, run=None):
        command=self.command(cwd)
        env=None
        command+=['--profile','acp']
        # The stock ACP profile projects committed messages only. This companion
        # subscribes to public transient frames without patching the installed CLI.
        from provider_manager import private_write
        live_patch=self.folder/'live-events.yml'
        private_write(live_patch,[{'insert':[{'id':'ai-xs-live-events','name':str(Path(__file__).with_name('dsh_live_events.mjs'))}]}])
        command+=['--patch',str(live_patch)]
        if getattr(self,'providers',None):
            command+=['--patch',str(self.providers.folder/'deepseek-provider.yml')]
            env={**os.environ,'AI_XS_DEEPSEEK_API_KEY':self.providers.selected('deepseek').get('key','')}
        if run is not None:
            mode = validate_permission(run['data'].get('permissionMode', 'ask'))
            permission_patch = self.folder / ('permissions-' + mode + '.yml')
            # Android's App sandbox is the isolation boundary. Keep approval prompts
            # independent from the unavailable nested Linux sandbox backend.
            preset = 'ai-xs-ask' if mode == 'ask' else 'danger-full-access'
            patch = [
                {'id':'sandbox-policy','config':{'mode':'danger-full-access'}},
                {'id':'approval','config':{'policy':'ask' if mode == 'ask' else 'never'}},
                {'id':'permission','config':{'presets':{'ai-xs-ask':{
                    'sandbox':'danger-full-access','approval':'ask','name':'询问权限'}},'defaultPreset':preset}}]
            from provider_manager import private_write
            private_write(permission_patch, patch)
            command += ['--patch', str(permission_patch)]
        rpc = RpcProcess(command,cwd,callback,env=env)
        try:
            if run is not None:
                with self.lock:
                    run['rpc']=rpc;run['process']=rpc.process
                    stopping=run['stopping']
                if stopping:raise RuntimeError('任务已停止')
            result = rpc.request('initialize',{'protocolVersion':1,'clientCapabilities':{},'clientInfo':{'name':'ai-xs','version':'1.0'}},timeout=90)
            return rpc,result
        except Exception:
            rpc.close(); raise

    @staticmethod
    def _model_id(value):
        # DSH uses a JSON [provider, model] tuple as its ACP option value.
        # Web profiles and saved threads use the plain model ID.
        try:
            pair=json.loads(value)
        except (ValueError,TypeError):
            return value
        return pair[1] if isinstance(pair,list) and len(pair)==2 and all(isinstance(p,str) for p in pair) else value

    @staticmethod
    def _choices(options):
        model = next((o for o in options if o['id']=='model'),{})
        effort = next((o for o in options if o['id']=='reasoning_effort'),{})
        def flatten(values):
            for choice in values:
                if 'options' in choice: yield from flatten(choice['options'])
                else: yield choice
        return [{'id':DeepSeekBridge._model_id(m['value']),'model':DeepSeekBridge._model_id(m['value']),
            'nativeValue':m['value'],'displayName':m.get('name',m['value']),
            'isDefault':m['value']==model.get('currentValue'),'inputModalities':['text'],
            'defaultReasoningEffort':effort.get('currentValue') or 'high',
            'supportedReasoningEfforts':[{'reasoningEffort':e['value']} for e in flatten(effort.get('options',[])) if e['value']]}
            for m in flatten(model.get('options',[]))]

    def model_list(self):
        if getattr(self,'providers',None):
            return self.providers.model_list(self.engine,{})
        if self.models is not None: return {'data':self.models}
        rpc, _ = self._connect(str(Path.home()))
        try:
            session = rpc.request('session/new',{'cwd':str(Path.home()),'mcpServers':[]},timeout=90)
            self.models = self._choices(session.get('configOptions',[]))
            if not self.models: raise RuntimeError('DeepSeek 未返回模型列表，请检查 dsh 配置')
            rpc.request('session/close',{'sessionId':session['sessionId']})
            return {'data':self.models}
        finally: rpc.close()

    def call(self, method, params):
        if method=='usage/read':return self._read_usage(params)
        if method=='thread/start' and params.get('sandbox') not in (None,'inherit','danger-full-access'):
            raise ValueError('DeepSeek ACP 不支持网页沙箱限制，请在设置中选择沿用本机权限后重试')
        if method in ('thread/fork','thread/revert'):
            result = super().call(method,params)
            data = self._read(result['thread']['id'])
            # ACP cannot rewind/fork native history. Replay the retained transcript
            # into a fresh native session, without repeating prior tool execution.
            data['nativeStarted']=False;data['nativeSession']=str(uuid.uuid4())
            data.pop('resumeFrom',None);data.pop('resumeAt',None);self._save(data)
            return result
        if method=='turn/interrupt':
            with self.lock:
                run=self.runs.get(params.get('threadId'))
                if run and run['turn']['id']==params.get('turnId'):
                    run['stopping']=True
                    threading.Thread(target=self._stop_later,args=(run['data']['id'],run),daemon=True).start()
                    if run.get('rpc') and run.get('sessionId'):
                        try:run['rpc'].notify('session/cancel',{'sessionId':run['sessionId']})
                        except (OSError,ValueError,RuntimeError):self._finish(run,'interrupted')
            return {}
        if method=='turn/steer':
            with self.lock:
                run=self.runs.get(params.get('threadId'))
                if not run or run['turn']['id']!=params.get('expectedTurnId') or run['stopping']:
                    raise ValueError('DeepSeek 当前任务已结束或正在停止，消息未引入')
                if not run.get('prompting'):raise ValueError('DeepSeek 正在初始化或收尾，请稍后引入')
                content=self._content(params['input'])
                if any(p['type']=='image' for p in content) and not run.get('imageEnabled'):raise ValueError('当前 DeepSeek 模型不支持图片输入')
                # Stock ACP admits only one prompt. Cancel to quiescence, then
                # continue the same native session; never send concurrent prompts.
                run['rpc'].notify('session/cancel',{'sessionId':run['sessionId']})
                run.setdefault('guidance',[]).append(copy.deepcopy(params))
                item={'id':uuid.uuid4().hex,'type':'userMessage','clientId':params.get('clientUserMessageId'),'content':copy.deepcopy(params['input'])}
                run['turn']['items'].append(item);self._save(run['data'])
                self._notify('item/completed',{'threadId':run['data']['id'],'turnId':run['turn']['id'],'item':item})
                return {'turnId':run['turn']['id'],'delivery':'interrupt_continue'}
        return super().call(method,params)

    def _start(self, data, params):
        tid=data['id']
        if tid in self.runs: raise ValueError('DeepSeek 正在运行，请排队发送')
        if data.get('archived'): raise ValueError('请先恢复已归档的会话')
        if data.get('sandbox') not in (None,'inherit','danger-full-access'):
            raise ValueError('DeepSeek ACP 不支持网页只读沙箱，请在设置中选择沿用本机权限后重试')
        self._content(params['input'])
        turn={'id':'dt-'+uuid.uuid4().hex,'status':'inProgress','items':[]}
        item={'id':uuid.uuid4().hex,'type':'userMessage','clientId':params.get('clientUserMessageId'),'content':params['input']}
        turn['items'].append(item);data['turns'].append(turn);data['model']=params.get('model') or data['model'];data['updatedAt']=time.time()
        if not data['preview']:data['preview']=next((p['text'][:100] for p in params['input'] if p['type']=='text'),'图片消息')
        run={'data':data,'turn':turn,'rpc':None,'process':None,'ready':False,'stopping':False,'requests':{}}
        self.runs[tid]=run;self._save(data)
        self._notify('turn/started',{'threadId':tid,'turn':copy.deepcopy(turn),'cwd':data['cwd']})
        self._progress(run,'starting')
        self._notify('item/completed',{'threadId':tid,'turnId':turn['id'],'item':item})
        threading.Thread(target=self._execute,args=(run,params),daemon=True).start()
        return {'turn':copy.deepcopy(turn)}

    def _execute(self, run, params):
        rpc=None;data=run['data'];tid=data['id'];usage_done=threading.Event()
        try:
            rpc,init=self._connect(data['cwd'],lambda message:self._message(run,message),run)
            run['rpc']=rpc;run['process']=rpc.process
            run['imageEnabled']=bool(init.get('agentCapabilities',{}).get('promptCapabilities',{}).get('image'))
            if run['stopping']:raise RuntimeError('任务已停止')
            servers=self.mcp.acp_servers() if getattr(self,'mcp',None) else []
            request={'cwd':data['cwd'],'mcpServers':servers}
            revision=self.providers.document()['engines']['deepseek']['revision'] if getattr(self,'providers',None) else None
            if revision and data.get('providerRevision') not in (None,revision):data['nativeStarted']=False
            permission_mode = data.get('permissionMode', 'ask')
            if data.get('nativePermissionMode') not in (None,permission_mode):data['nativeStarted']=False
            if data.get('nativeStarted'):
                request['sessionId']=data['nativeSession'];session=rpc.request('session/resume',request,timeout=90)
                session['sessionId']=data['nativeSession']
            else:session=rpc.request('session/new',request,timeout=90)
            run['sessionId']=session['sessionId'];run['ready']=True
            options=session.get('configOptions',[])
            model=params.get('model') or data.get('model')
            available=self._choices(options)
            selected=next((m for m in available if m['nativeValue']==model),None)
            if selected is None:
                selected=next((m for m in available if m['model']==self._model_id(model)),None)
            if getattr(self,'providers',None):
                if not available:raise RuntimeError('DeepSeek 未返回模型选项，请检查 API 配置')
                if selected is None:
                    if params.get('strictModel'):raise ValueError('DeepSeek 未返回指定模型，不会自动换模型')
                    selected=next((m for m in available if m['isDefault']),available[0])
            if selected:
                model=selected['nativeValue'];data['model']=selected['model']
            if model and model!='default':
                options=rpc.request('session/set_config_option',{'sessionId':run['sessionId'],'configId':'model','value':model})['configOptions']
            effort=params.get('effort')
            option=next((o for o in options if o['id']=='reasoning_effort'),{})
            if params.get('strictModel') and effort and not any(e.get('value')==effort for e in option.get('options',[])):
                raise ValueError('DeepSeek 不支持指定思考强度，不会自动降级')
            if effort and any(e.get('value')==effort for e in option.get('options',[])):
                rpc.request('session/set_config_option',{'sessionId':run['sessionId'],'configId':'reasoning_effort','value':effort})
            prompt=[]
            instructions=data.get('developerInstructions','')
            if instructions:prompt.append({'type':'text','text':'项目要求与对话背景：\n'+instructions})
            if not data.get('nativeStarted') and len(data['turns'])>1:
                history=[]
                for old in data['turns'][:-1]:
                    for item in old['items']:
                        if item['type']=='userMessage':history.append('用户：'+''.join(p.get('text','') for p in item.get('content',[])))
                        elif item['type']=='agentMessage':history.append('助手：'+item.get('text',''))
                prompt.append({'type':'text','text':'以下是保留的对话记录，已完成操作仅作背景：\n'+'\n\n'.join(history)})
            for part in self._content(params['input']):
                if part['type']=='text':prompt.append(part)
                elif part['type']=='image':
                    if not init.get('agentCapabilities',{}).get('promptCapabilities',{}).get('image'):raise ValueError('当前 DeepSeek 模型不支持图片输入')
                    source=part['source'];prompt.append({'type':'image','data':source['data'],'mimeType':source['media_type']})
            if run['stopping']:raise RuntimeError('任务已停止')
            data['nativeSessions']=list(dict.fromkeys(data.get('nativeSessions',[])+
                ([data['nativeSession']] if data.get('nativeSession') else [])+[run['sessionId']]))
            data['nativeSession']=run['sessionId'];data['nativeStarted']=True;data['nativePermissionMode']=permission_mode
            if revision:data['providerRevision']=revision
            self._save(data)
            def usage_updates():
                while not usage_done.wait(.75):
                    with self.lock:
                        if self.runs.get(tid) is not run:return
                        self._refresh_usage(data,notify=True)
            threading.Thread(target=usage_updates,name='deepseek-usage',daemon=True).start()
            self._progress(run,'waiting')
            def prompt_sent():
                with self.lock:run['prompting']=True
            while True:
                with self.lock:
                    if run['stopping']:break
                    run['prompting']=False
                rpc.request('session/prompt',{'sessionId':run['sessionId'],'prompt':prompt},timeout=3600,on_sent=prompt_sent)
                with self.lock:run['prompting']=False
                deadline=time.monotonic()+3600
                while True:
                    with self.lock:
                        if run.get('stopping') or run.get('guidance') or not run.get('activeChildTasks'):break
                    if rpc.process.poll() is not None:raise RuntimeError('DeepSeek 后台子任务进程已退出')
                    if time.monotonic()>deadline:raise TimeoutError('DeepSeek 后台子任务超时')
                    usage_done.wait(.2)
                with self.lock:
                    guidance=run.pop('guidance',[]) if not run['stopping'] else []
                    if not guidance:
                        run['prompting']=False
                        self._refresh_usage(data,notify=True)
                        self._finish(run,'interrupted' if run['stopping'] else 'completed',cleanup=False)
                        break
                    self._clear_requests(run)
                    prompt=[]
                    for message in guidance:
                        for part in self._content(message['input']):
                            if part['type']=='text':prompt.append(part)
                            elif part['type']=='image':prompt.append({'type':'image','data':part['source']['data'],'mimeType':part['source']['media_type']})
            if run['stopping']:
                with self.lock:self._finish(run,'interrupted',cleanup=False)
        except Exception as error:
            with self.lock:
                self._refresh_usage(data,notify=True)
                self._finish(run,'interrupted' if run['stopping'] else 'failed',None if run['stopping'] else str(error),cleanup=False)
        finally:
            usage_done.set()
            if rpc:rpc.close()

    @staticmethod
    def _tool_id(run, native_id):
        return run.get('liveToolIds', {}).get(native_id, native_id)

    def _discard_tool_draft(self, run, item):
        if item.get('type') != 'toolCallDraft':return
        item.update(type='runtimeStatus', status='discarded',
                    tool='工具准备中断 · '+item.get('tool', '工具')+' · 未执行',
                    progressMessage='参数生成未完成，未提交执行')
        self._item(run, item)

    def _live_stream(self, run, params):
        if params.get('sessionId')!=run.get('sessionId'):return
        frame=params.get('frame') or {};aid=frame.get('attemptId')
        if not isinstance(aid,str) or not aid:return
        streams=run.setdefault('liveStreams',{})
        stream=streams.setdefault(aid,{'revision':-1,'items':{},'kinds':set()})
        revision=frame.get('revision',0)
        if not isinstance(revision,int) or revision<=stream['revision']:return
        stream['revision']=revision
        if frame.get('type')=='end':
            outcome=frame.get('outcome') or {}
            committed=outcome.get('kind')=='committed' and outcome.get('eventType')=='assistant/message'
            status='completed' if committed else 'failed'
            for item in stream['items'].values():
                if not committed and item.get('type') == 'toolCallDraft':
                    self._discard_tool_draft(run, item)
                    continue
                if (item['type'] in ('reasoning','agentMessage') or not committed) and item.get('status')=='inProgress':
                    item['status']=status;self._item(run,item)
            return
        chunk=frame.get('chunk') or {};kind=chunk.get('type')
        if frame.get('type')!='chunk':return
        index=chunk.get('index')
        if not isinstance(index,int) or not 0<=index<=10000:return
        block=chunk.get('block') or {}
        blocktype=chunk.get('blockType') or block.get('type') or {
            'text-delta':'text','reasoning-delta':'reasoning','tool-call-delta':'tool-call'}.get(kind)
        if blocktype not in ('text','reasoning','tool-call'):return
        item=stream['items'].get(index)
        if item is None:
            # Stream identity survives native IDs changing as fragments arrive,
            # and prevents retried attempts reusing an ID from sharing a card.
            iid='live:'+aid+':'+str(index)
            item={'id':iid,'type':{'text':'agentMessage','reasoning':'reasoning','tool-call':'toolCallDraft'}[blocktype],
                  'status':'inProgress'}
            if blocktype!='tool-call':item['text']=''
            else:item.update(tool=chunk.get('name') or block.get('name') or '工具',partialInput='',arguments={},progressMessage='正在生成工具参数')
            stream['items'][index]=item;run['turn']['items'].append(item)
            self._notify('item/started',{'threadId':run['data']['id'],'turnId':run['turn']['id'],'item':copy.deepcopy(item)})
        if blocktype == 'tool-call':
            native_id=chunk.get('id') or block.get('id')
            if native_id:run.setdefault('liveToolIds', {})[native_id]=item['id']
        if blocktype in ('text','reasoning'):
            stream['kinds'].add('agent_thought_chunk' if blocktype=='reasoning' else 'agent_message_chunk')
            if kind in ('text-delta','reasoning-delta'):
                text=chunk.get('text','');item['text']+=text
                if blocktype=='reasoning':item['content']=[item['text']];item['summary']=[]
                self._progress(run,'thinking' if blocktype=='reasoning' else 'responding')
                self._notify('item/reasoning/textDelta' if blocktype=='reasoning' else 'item/agentMessage/delta',
                    {'threadId':run['data']['id'],'turnId':run['turn']['id'],'itemId':item['id'],'contentIndex':0,'delta':text})
            elif kind=='block-end':
                item['text']=block.get('text',item.get('text',''))
                if blocktype=='reasoning':item['content']=[item['text']];item['summary']=[]
                item['status']='completed';self._item(run,item)
        elif kind in ('tool-call-delta','block-end'):
            item['tool']=chunk.get('name') or block.get('name') or item['tool']
            item['partialInput']+=chunk.get('argumentsDelta','')
            if kind=='block-end':
                arguments=block.get('arguments','{}')
                if isinstance(arguments,str):
                    try:arguments=json.loads(arguments)
                    except ValueError:pass
                item.update(arguments=arguments,partialInput='',progressMessage='等待执行')
            self._item(run,item);self._progress(run,'preparingTool',item['tool'])
        now=time.monotonic()
        if now-run.get('lastStreamSave',0)>=.25:
            self._save(run['data']);run['lastStreamSave']=now

    def _message(self, run, message):
        with self.lock:
            if self.runs.get(run['data']['id']) is not run.get('rootRun',run):return
            if not run.get('rootRun') and self._child_message(run,message):return
            params=message.get('params',{});method=message.get('method');tid=run['data']['id'];turn=run['turn']['id']
            if method=='aixs/runtimeError':
                self._item(run,{'id':'dsh-runtime-error','type':'runtimeStatus','tool':'执行错误','status':'failed','text':params.get('message','')})
                return
            if method=='session/request_permission':
                if run['data'].get('permissionMode') == 'auto':
                    choice = next((o['optionId'] for o in params.get('options',[]) if o.get('kind') == 'allow_once'), None)
                    outcome = {'outcome':'selected','optionId':choice} if choice else {'outcome':'cancelled'}
                    run['rpc'].write({'jsonrpc':'2.0','id':message['id'],'result':{'outcome':outcome}})
                    return
                rid='deepseek:'+tid+':'+str(message['id'])
                tool=params.get('toolCall',{})
                current=next((i for i in run['turn']['items'] if i['id']==self._tool_id(run,tool.get('toolCallId'))),{})
                arguments=tool.get('rawInput',current.get('arguments',{}))
                title=tool.get('title') or current.get('tool') or 'DeepSeek 工具操作'
                p={'threadId':tid,'turnId':turn,'engine':'deepseek','tool':title,
                    'command':arguments.get('command',title) if isinstance(arguments,dict) else title,
                    'arguments':copy.deepcopy(arguments),'locations':copy.deepcopy(tool.get('locations',current.get('locations',[]))),
                    'reason':tool.get('description') or '', 'cwd':run['data']['cwd']}
                run['requests'][rid]={'rid':message['id'],'options':params.get('options',[])}
                self.app.approvals[rid]={'id':rid,'method':'item/commandExecution/requestApproval','params':p}
                self.app._emit({'type':'approval',**self.app.approvals[rid]});return
            if method=='aixs/assistantStream':self._live_stream(run,params);return
            if method in ('aixs/toolProgress','aixs/toolOutput','aixs/runtimeProgress'):
                if params.get('sessionId')!=run.get('sessionId'):return
                if method=='aixs/runtimeProgress':
                    if params.get('phase') in ('waiting','retrying','compacting'):self._progress(run,params['phase'],params.get('detail',''))
                    return
                iid=self._tool_id(run,params.get('itemId'))
                if not isinstance(iid,str) or not iid:return
                item=next((i for i in run['turn']['items'] if i['id']==iid),None)
                if item is None:
                    item={'id':iid,'type':'dynamicToolCall','tool':params.get('tool') or '执行工具','status':'inProgress'}
                    run['turn']['items'].append(item)
                    self._notify('item/started',{'threadId':tid,'turnId':turn,'item':copy.deepcopy(item)})
                item['type']='dynamicToolCall'
                if method=='aixs/toolOutput':
                    delta=params.get('delta','')
                    if not isinstance(delta,str):return
                    item['aggregatedOutput']=item.get('aggregatedOutput','')+delta
                    self._notify('item/commandExecution/outputDelta',{'threadId':tid,'turnId':turn,'itemId':iid,'delta':delta})
                elif item.get('status','inProgress')=='inProgress':
                    item['elapsedSeconds']=params.get('elapsedSeconds',0)
                    item['progressMessage']='执行中 · '+str(item['elapsedSeconds'])+' 秒'
                    self._item(run,item);self._progress(run,'tool',item.get('tool','工具'))
                return
            if method=='aixs/assistantCommit':
                if params.get('sessionId')==run.get('sessionId'):
                    stream=run.get('liveStreams',{}).get(params.get('attemptId'))
                    if stream and params.get('messageId'):
                        run.setdefault('liveCommitted',{})[params['messageId']]=set(stream['kinds'])
                return
            if method!='session/update':
                if 'id' in message and run.get('rpc'):run['rpc'].write({'jsonrpc':'2.0','id':message['id'],'error':{'code':-32601,'message':'Unsupported client request'}})
                return
            if params.get('sessionId') not in (None,run.get('sessionId')):return
            u=params.get('update',{});kind=u.get('sessionUpdate')
            if kind=='plan':
                self._notify('turn/plan/updated',{'threadId':tid,'turnId':turn,'plan':[
                    {'step':entry.get('content',''),'status':{'in_progress':'inProgress'}.get(entry.get('status'),entry.get('status','pending'))}
                    for entry in u.get('entries',[]) if isinstance(entry,dict)]})
                self._progress(run,'planning')
            elif kind in ('agent_message_chunk','agent_thought_chunk'):
                if kind in run.get('liveCommitted',{}).get(u.get('messageId'),set()):return
                content=u.get('content',{})
                if content.get('type')!='text':return
                # Standard ACP chunks omit messageId. Keep consecutive chunks
                # in one message instead of rendering each token separately.
                segment=run.get('chunk')
                if u.get('messageId'):iid=str(u['messageId'])+'-'+kind
                elif segment and segment[0]==kind:iid=segment[1]
                else:iid=uuid.uuid4().hex+'-'+kind
                run['chunk']=(kind,iid)
                item=next((i for i in run['turn']['items'] if i['id']==iid),None)
                fresh=item is None
                if fresh:item={'id':iid,'type':'reasoning' if kind=='agent_thought_chunk' else 'agentMessage','text':'','status':'inProgress'};run['turn']['items'].append(item)
                for previous in run['turn']['items']:
                    if previous is not item and previous.get('type') in ('reasoning','agentMessage') and previous.get('status')=='inProgress':
                        previous['status']='completed';self._item(run,previous)
                if fresh:self._notify('item/started',{'threadId':tid,'turnId':turn,'item':copy.deepcopy(item)})
                text=content.get('text','');item['text']+=text
                if item['type']=='reasoning':item['content']=[item['text']];item['summary']=[]
                # Send only new text; cumulative snapshots per token flood Android's pipe.
                self._notify('item/reasoning/textDelta' if item['type']=='reasoning' else 'item/agentMessage/delta',
                    {'threadId':tid,'turnId':turn,'itemId':iid,'contentIndex':0,'delta':text})
            elif kind in ('tool_call','tool_call_update'):
                run.pop('chunk',None)
                iid=self._tool_id(run,u['toolCallId']);item=next((i for i in run['turn']['items'] if i['id']==iid),{'id':iid})
                item['type']='dynamicToolCall'
                item['partialInput']=''
                if 'title' in u:item['tool']=u['title']
                if 'rawInput' in u:item['arguments']=u['rawInput']
                if 'locations' in u:item['locations']=copy.deepcopy(u['locations'])
                if 'status' in u:item['status']='inProgress' if u['status'] in ('pending','in_progress') else u['status']
                else:item.setdefault('status','inProgress')
                for previous in run['turn']['items']:
                    if previous.get('type') in ('reasoning','agentMessage') and previous.get('status')=='inProgress':
                        previous['status']='completed';self._item(run,previous)
                if 'content' in u:item['result']=u['content']
                if item.get('status') not in ('inProgress','pending','in_progress'):item.pop('progressMessage',None)
                self._item(run,item)
            elif kind=='usage_update':
                usage=copy.deepcopy(run['data'].get('tokenUsage') or {})
                usage.update(last={'totalTokens':u['used']} if 'used' in u else {},modelContextWindow=u.get('size'))
                usage.setdefault('total',{})
                run['data']['tokenUsage']=usage;run['data']['usageAt']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
                self._notify('thread/tokenUsage/updated',{'threadId':tid,'tokenUsage':usage,'model':run['data'].get('model')})
            # Finalization always flushes. Checkpoint streaming text at most four times
            # per second rather than rewriting the entire session for every token.
            now=time.monotonic()
            if kind not in ('agent_message_chunk','agent_thought_chunk') or now-run.get('lastStreamSave',0)>=.25:
                self._save(run['data']);run['lastStreamSave']=now

    def respond(self, approval_id, result):
        with self.lock:
            for run in self.runs.values():
                request=run['requests'].get(approval_id)
                if not request:continue
                kind='allow_once' if result.get('decision')=='accept' else 'reject_once'
                choice=next((o['optionId'] for o in request['options'] if o.get('kind')==kind),None)
                outcome={'outcome':'selected','optionId':choice} if choice else {'outcome':'cancelled'}
                run['rpc'].write({'jsonrpc':'2.0','id':request['rid'],'result':{'outcome':outcome}})
                run['requests'].pop(approval_id);self.app.approvals.pop(approval_id,None)
                self._notify('serverRequest/resolved',{'threadId':run['data']['id'],'requestId':approval_id});return
            raise ValueError('DeepSeek 操作审批已结束')

    def _finish(self, run, status, error=None, cleanup=True):
        if self.runs.get(run['data']['id']) is not run:return
        for item in run['turn']['items']:
            self._discard_tool_draft(run,item)
        super()._finish(run,status,error,cleanup=False)
        if cleanup and run.get('rpc'):threading.Thread(target=run['rpc'].close,daemon=True).start()

    def close(self):
        with self.lock:
            runs=list(self.runs.values())
            for run in runs:run['stopping']=True;self._finish(run,'interrupted',cleanup=False)
        for run in runs:
            if run.get('rpc'):run['rpc'].close()
