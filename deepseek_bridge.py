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


class DeepSeekBridge(ClaudeBridge):
    engine = 'deepseek'
    label = 'DeepSeek'

    def _scan_native_sessions(self):
        """List persisted ACP sessions through the CLI's own session/list API."""
        root = Path.home() / '.dsh' / 'sessions'
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
        try:
            for folder in (Path.home() / '.dsh' / 'sessions').glob('*/' + session_id):
                for name in ('session.v4.jsonl.zstd', 'session.v4.jsonl'):
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
                raise RuntimeError('DeepSeek 尚未安装或启动程序已失效，请到设置 → Agent 安装 → DeepSeek 安装或重新导入部署包')
            return value
        binary=shutil.which('dsh')
        if not binary:raise RuntimeError('未找到 DeepSeek，请在设置 → Agent 安装中安装')
        return [binary]

    def _connect(self, cwd, callback=None, run=None):
        command=self.command(cwd)
        env=None
        command+=['--profile','acp']
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
            raise ValueError('DeepSeek ACP 不支持运行中插入引导；请排队发送，或停止后继续')
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
        self._notify('turn/started',{'threadId':tid,'turn':copy.deepcopy(turn)})
        self._progress(run,'starting')
        self._notify('item/completed',{'threadId':tid,'turnId':turn['id'],'item':item})
        threading.Thread(target=self._execute,args=(run,params),daemon=True).start()
        return {'turn':copy.deepcopy(turn)}

    def _execute(self, run, params):
        rpc=None;data=run['data'];tid=data['id']
        try:
            rpc,init=self._connect(data['cwd'],lambda message:self._message(run,message),run)
            run['rpc']=rpc;run['process']=rpc.process
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
                    selected=next((m for m in available if m['isDefault']),available[0])
            if selected:
                model=selected['nativeValue'];data['model']=selected['model']
            if model and model!='default':
                options=rpc.request('session/set_config_option',{'sessionId':run['sessionId'],'configId':'model','value':model})['configOptions']
            effort=params.get('effort')
            option=next((o for o in options if o['id']=='reasoning_effort'),{})
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
            data['nativeSession']=run['sessionId'];data['nativeStarted']=True;data['nativePermissionMode']=permission_mode
            if revision:data['providerRevision']=revision
            self._save(data)
            self._progress(run,'waiting')
            rpc.request('session/prompt',{'sessionId':run['sessionId'],'prompt':prompt},timeout=3600)
            with self.lock:self._finish(run,'interrupted' if run['stopping'] else 'completed',cleanup=False)
        except Exception as error:
            with self.lock:self._finish(run,'interrupted' if run['stopping'] else 'failed',None if run['stopping'] else str(error),cleanup=False)
        finally:
            if rpc:rpc.close()

    def _message(self, run, message):
        with self.lock:
            if self.runs.get(run['data']['id']) is not run:return
            params=message.get('params',{});method=message.get('method');tid=run['data']['id'];turn=run['turn']['id']
            if method=='session/request_permission':
                if run['data'].get('permissionMode') == 'auto':
                    choice = next((o['optionId'] for o in params.get('options',[]) if o.get('kind') == 'allow_once'), None)
                    outcome = {'outcome':'selected','optionId':choice} if choice else {'outcome':'cancelled'}
                    run['rpc'].write({'jsonrpc':'2.0','id':message['id'],'result':{'outcome':outcome}})
                    return
                rid='deepseek:'+tid+':'+str(message['id'])
                tool=params.get('toolCall',{})
                current=next((i for i in run['turn']['items'] if i['id']==tool.get('toolCallId')),{})
                arguments=tool.get('rawInput',current.get('arguments',{}))
                title=tool.get('title') or current.get('tool') or 'DeepSeek 工具操作'
                p={'threadId':tid,'turnId':turn,'engine':'deepseek','tool':title,
                    'command':arguments.get('command',title) if isinstance(arguments,dict) else title,
                    'arguments':copy.deepcopy(arguments),'locations':copy.deepcopy(tool.get('locations',current.get('locations',[]))),
                    'reason':tool.get('description') or '', 'cwd':run['data']['cwd']}
                run['requests'][rid]={'rid':message['id'],'options':params.get('options',[])}
                self.app.approvals[rid]={'id':rid,'method':'item/commandExecution/requestApproval','params':p}
                self.app._emit({'type':'approval',**self.app.approvals[rid]});return
            if method!='session/update':
                if 'id' in message and run.get('rpc'):run['rpc'].write({'jsonrpc':'2.0','id':message['id'],'error':{'code':-32601,'message':'Unsupported client request'}})
                return
            u=params.get('update',{});kind=u.get('sessionUpdate')
            if kind in ('agent_message_chunk','agent_thought_chunk'):
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
                if item is None:item={'id':iid,'type':'reasoning' if kind=='agent_thought_chunk' else 'agentMessage','text':''};run['turn']['items'].append(item)
                item['text']+=content.get('text','')
                if item['type']=='reasoning':item['content']=[item['text']];item['summary']=[]
                self._item(run,item)
            elif kind in ('tool_call','tool_call_update'):
                run.pop('chunk',None)
                iid=u['toolCallId'];item=next((i for i in run['turn']['items'] if i['id']==iid),{'id':iid,'type':'dynamicToolCall'})
                if 'title' in u:item['tool']=u['title']
                if 'rawInput' in u:item['arguments']=u['rawInput']
                if 'locations' in u:item['locations']=copy.deepcopy(u['locations'])
                item['status']='inProgress' if u.get('status') in ('pending','in_progress') else u.get('status','inProgress')
                if 'content' in u:item['result']=u['content']
                self._item(run,item)
            elif kind=='usage_update':
                usage={'total':{},'last':{'totalTokens':u.get('used',0)},'modelContextWindow':u.get('size')}
                run['data']['tokenUsage']=usage;run['data']['usageAt']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
                self._notify('thread/tokenUsage/updated',{'threadId':tid,'tokenUsage':usage})
            self._save(run['data'])

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
        super()._finish(run,status,error,cleanup=False)
        if cleanup and run.get('rpc'):threading.Thread(target=run['rpc'].close,daemon=True).start()

    def close(self):
        with self.lock:
            runs=list(self.runs.values())
            for run in runs:run['stopping']=True;self._finish(run,'interrupted',cleanup=False)
        for run in runs:
            if run.get('rpc'):run['rpc'].close()
