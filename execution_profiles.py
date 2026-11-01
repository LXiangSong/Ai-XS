"""Resolve immutable per-attempt configurations without changing global providers."""
import copy
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
from provider_manager import PROTOCOLS, private_write

ENGINES=('codex','claude','deepseek')


def participant(value):
    if not isinstance(value,dict):raise ValueError('参与者格式无效')
    engine=value.get('engine');model=value.get('model','').strip()
    if engine not in ENGINES:raise ValueError('未知 Agent')
    if not re.fullmatch(r'[^\s\x00-\x1f]{1,200}',model):raise ValueError('请为参与者选择模型')
    permission=value.get('permission','ask')
    if permission not in ('ask','auto'):raise ValueError('权限模式无效')
    effort=value.get('effort','')
    if effort not in ('','off','minimal','low','medium','high','xhigh','max'):raise ValueError('思考强度无效')
    mode=value.get('apiMode','current')
    if mode not in ('current','official','thirdparty'):raise ValueError('API 来源无效')
    return {'id':str(value.get('id') or ''),'name':str(value.get('name') or engine)[:80],
            'role':str(value.get('role') or '')[:500],'engine':engine,'model':model,
            'permission':permission,'effort':effort,'apiMode':mode}


class ExecutionProfiles:
    def __init__(self, providers, enabled):self.providers,self.enabled=providers,enabled

    def group(self,value):
        engine=value['engine'];entry=self.providers.document()['engines'][engine]
        mode=entry['mode'] if value.get('apiMode','current')=='current' else value['apiMode']
        url=urlsplit(entry['profiles'][mode].get('baseUrl',''))
        origin=url.scheme+'://'+url.netloc.lower() if url.netloc else engine+':'+mode
        return hashlib.sha256(origin.encode()).hexdigest()[:16]

    def resolve(self, value, folder):
        p=participant(value);engine=p['engine']
        if not self.enabled(engine):raise ValueError(engine+' 已停止，请先在 Agent 管理启动')
        document=self.providers.document();entry=document['engines'][engine]
        mode=entry['mode'] if p['apiMode']=='current' else p['apiMode']
        selected=copy.deepcopy(entry['profiles'][mode])
        if not selected.get('key') or not selected.get('baseUrl'):raise ValueError(engine+' 的 '+mode+' API 尚未配置')
        if selected['protocol'] not in PROTOCOLS[engine]:raise ValueError('该 Agent 不支持所选 API 协议')
        capabilities=self.providers.model_list(engine,{}).get('data',[])
        metadata=next((m for m in capabilities if m.get('model')==p['model']),None)
        if p['effort'] and metadata:
            available=[x['reasoningEffort'] for x in metadata.get('supportedReasoningEfforts',[])]
            if available and p['effort'] not in available:raise ValueError(p['model']+' 不支持思考强度 '+p['effort'])
        # Only the selected Agent receives credentials; other profiles remain blank.
        snapshot=self.providers._import()
        selected['models']=[p['model']];snapshot[engine]={'mode':mode,'revision':entry['revision'],'profiles':
            {'official':copy.deepcopy(selected),'thirdparty':copy.deepcopy(selected)}}
        private_write(Path(folder)/'providers.json',{'version':1,'engines':snapshot})
        public=dict(p,apiMode=mode,providerRevision=entry['revision'],protocol=selected['protocol'],providerName=selected['name'])
        public['sourceGroup']=self.group(value)
        return public
