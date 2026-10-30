"""Private API-provider profiles for the native Ai-XS app."""
from __future__ import annotations
import copy
import json
import os
from pathlib import Path
import re
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

UBUNTU_HOME=Path.home()
DEFAULTS={
 'codex':{'name':'OpenAI','baseUrl':'https://api.openai.com/v1','protocol':'responses','models':['gpt-6.1-sol']},
 'claude':{'name':'Anthropic','baseUrl':'https://api.anthropic.com','protocol':'anthropic-messages','models':['claude-sonnet-5-5']},
 'deepseek':{'name':'DeepSeek','baseUrl':'https://api.deepseek.com/anthropic','protocol':'anthropic-messages','models':['deepseek-flash','deepseek-v4-pro']},
}
PROTOCOLS={'codex':['responses'],'claude':['anthropic-messages'],'deepseek':['anthropic-messages','openai-completions','responses']}
USER_AGENT='Ai-XS/0.6.0'


def private_write(path,value):
 path=Path(path);path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
 temp=path.with_suffix(path.suffix+'.tmp');temp.write_text(json.dumps(value,ensure_ascii=False,indent=2));temp.chmod(0o600);temp.replace(path)


def read_json(path):
 try:return json.loads(Path(path).read_text())
 except FileNotFoundError:return {}


class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None


class ProviderManager:
 def __init__(self,folder,home=None,ubuntu_home=None):
  self.folder=Path(folder);self.path=self.folder/'providers.json';self.lock=threading.RLock()
  self.home=Path(home) if home else Path.home();self.ubuntu_home=Path(ubuntu_home) if ubuntu_home else Path(os.environ.get('AI_XS_ENGINE_HOME',str(UBUNTU_HOME)))
  if not self.path.exists():private_write(self.path,{'version':1,'engines':self._import()})
  self._runtime_files()

 def _import(self):
  # A new app starts with blank API profiles; never inherit host accounts or keys.
  return {engine:{'mode':'thirdparty','revision':uuid.uuid4().hex,'profiles':{
   'official':dict(copy.deepcopy(default),key='',headers={}),
   'thirdparty':dict(copy.deepcopy(default),name='第三方 API',baseUrl='',key='',headers={})}}
   for engine,default in DEFAULTS.items()}

 def document(self):
  with self.lock:return read_json(self.path)

 def selected(self,engine):
  entry=self.document()['engines'][engine]
  return copy.deepcopy(entry['profiles'][entry['mode']])

 def read(self,engine):
  with self.lock:
   entry=self.document()['engines'][engine];profiles={}
   for mode,p in entry['profiles'].items():
    profiles[mode]={k:copy.deepcopy(p[k]) for k in ('name','baseUrl','protocol','models')}
    profiles[mode].update(keyConfigured=bool(p.get('key')),headersConfigured=bool(p.get('headers')))
   return {'engine':engine,'mode':entry['mode'],'profiles':profiles,'protocols':PROTOCOLS[engine]}

 def candidate(self,engine,params):
  mode=params.get('mode')
  if mode not in ('official','thirdparty'):raise ValueError('请选择官方 API 或第三方 API')
  old=self.document()['engines'][engine]['profiles'][mode];value=copy.deepcopy(old)
  for k in ('name','baseUrl','protocol','models'):
   if k in params:value[k]=copy.deepcopy(params[k])
  if mode=='official':value['baseUrl']=DEFAULTS[engine]['baseUrl'];value['name']=DEFAULTS[engine]['name'];value['protocol']=DEFAULTS[engine]['protocol']
  if not isinstance(value['baseUrl'],str):raise ValueError('API 地址格式不正确')
  value['baseUrl']=value['baseUrl'].strip().rstrip('/');url=urllib.parse.urlsplit(value['baseUrl'])
  if url.scheme not in ('https','http') or not url.hostname or url.username or url.password or url.query or url.fragment or any(c.isspace() for c in value['baseUrl']):raise ValueError('API 地址必须是 HTTP(S) 根地址，不可包含用户名、密码、查询参数或片段')
  try:url.port
  except ValueError:raise ValueError('API 地址端口不正确')
  if value['protocol'] not in PROTOCOLS[engine]:raise ValueError('该助手不支持所选 API 协议')
  if not isinstance(value['name'],str) or not 0<len(value['name'].strip())<=100:raise ValueError('服务名称需为 1 到 100 个字符')
  if not isinstance(value['models'],list) or not 1<=len(value['models'])<=100 or not all(isinstance(m,str) and re.fullmatch(r'[^\s\x00-\x1f]{1,200}',m) for m in value['models']):raise ValueError('请填写模型 ID，每行一个，最多 100 个')
  value['models']=list(dict.fromkeys(value['models']))
  key=params.get('key','')
  if not isinstance(key,str) or len(key)>8192 or any(c in key for c in '\r\n\0'):raise ValueError('API Key 格式不正确')
  if key.strip():value['key']=key.strip()
  elif old.get('baseUrl','').rstrip('/')!=value['baseUrl']:raise ValueError('修改 API 地址时请重新填写密钥')
  if not value.get('key'):raise ValueError('请填写 API Key')
  if old.get('baseUrl','').rstrip('/')!=value['baseUrl']:value['headers']={}
  if params.get('clearHeaders') is True:value['headers']={}
  if 'headers' in params:
   h=params['headers']
   if not isinstance(h,dict) or len(h)>30 or not all(isinstance(k,str) and re.fullmatch(r'[!#$%&\x27*+.^_`|~0-9A-Za-z-]+',k) and isinstance(v,str) and len(v)<8192 and not any(c in v for c in '\r\n\0') and k.lower() not in ('host','content-length','connection','transfer-encoding','content-type') for k,v in h.items()):raise ValueError('额外请求头格式不正确')
   value['headers']=copy.deepcopy(h)
  if engine=='deepseek' and mode=='official' and value.get('headers'):raise ValueError('DeepSeek 官方 API 不需要额外请求头，请清除后保存')
  return value

 def save(self,engine,params):
  with self.lock:
   value=self.candidate(engine,params);document=self.document();document['engines'][engine]['mode']=params['mode'];document['engines'][engine]['revision']=uuid.uuid4().hex;document['engines'][engine]['profiles'][params['mode']]=value
   try:
    private_write(self.path,document);self._runtime_files()
   except Exception:
    raise RuntimeError('私密 API 配置写入失败') from None
   return self.read(engine)

 def restore(self,document):
  with self.lock:private_write(self.path,document);self._runtime_files()

 def _runtime_files(self):
  # Claude Code appends /v1/messages itself; API gateway URLs often include /v1.
  claude=self.selected('claude');env={'ANTHROPIC_BASE_URL':claude['baseUrl'].removesuffix('/v1'),'ANTHROPIC_AUTH_TOKEN':'','ANTHROPIC_API_KEY':'','CLAUDE_CODE_OAUTH_TOKEN':'',
   'CLAUDE_CODE_USE_BEDROCK':'','CLAUDE_CODE_USE_VERTEX':'','CLAUDE_CODE_USE_FOUNDRY':''}
  env['ANTHROPIC_API_KEY' if self.document()['engines']['claude']['mode']=='official' else 'ANTHROPIC_AUTH_TOKEN']=claude.get('key','')
  env['ANTHROPIC_CUSTOM_HEADERS']='\n'.join(k+': '+v for k,v in claude.get('headers',{}).items())
  private_write(self.folder/'claude-provider.json',{'env':env,'model':claude['models'][0],'apiKeyHelper':'','forceLoginMethod':None})
  ds=self.selected('deepseek');models=[{'id':m,'name':m,'contextWindow':1000000,'maxTokens':32768,'input':['text'],'reasoningEfforts':{'off':None,'low':'low','high':'high','max':'max'}} for m in ds['models']]
  protocol={'responses':'openai-responses'}.get(ds['protocol'],ds['protocol'])
  provider={'displayName':ds['name'],'apiKeyEnv':'AI_XS_DEEPSEEK_API_KEY','api':protocol,'baseURL':ds['baseUrl'],'models':models,'headers':ds.get('headers',{}),'retryPolicy':{'mode':'normal','maxRetries':1}}
  if protocol=='openai-completions':provider['compat']={'thinkingFormat':'deepseek','supportsDeveloperRole':False,'maxTokensField':'max_tokens'}
  patch=[
   {'id':'llm-pi-ai','config':{'providers':{'ai-xs':provider}}},
   {'id':'acp','config':{'provider':'ai-xs','model':ds['models'][0]}},
   {'id':'agent-default-model','config':{'provider':'ai-xs','model':ds['models'][0]}}]
  if self.document()['engines']['deepseek']['mode']=='official':
   patch=[{'id':'llm-pi-ai','config':{'providers':{}}},
    {'id':'llm-deepseek','config':{'baseURL':ds['baseUrl'],'apiKeyEnv':'AI_XS_DEEPSEEK_API_KEY','maxTokens':32768,'models':[{'id':m,'name':m,'contextWindow':1000000,'maxTokens':32768,'inputModalities':['text']} for m in ds['models']]}},
    {'id':'acp','config':{'provider':'deepseek-official','model':ds['models'][0]}},
    {'id':'agent-default-model','config':{'provider':'deepseek-official','model':ds['models'][0]}}]
  private_write(self.folder/'deepseek-provider.yml',patch)

 def codex_launch(self):
  p=self.selected('codex');env=dict(os.environ);env['AI_XS_CODEX_API_KEY']=p.get('key','')
  provider={'name':p['name'],'base_url':p['baseUrl'] or DEFAULTS['codex']['baseUrl'],'wire_api':'responses','requires_openai_auth':False,'env_key':'AI_XS_CODEX_API_KEY'}
  extra={}
  for i,(k,v) in enumerate(p.get('headers',{}).items()):
   name='AI_XS_CODEX_HEADER_'+str(i);env[name]=v;extra[k]=name
  if extra:provider['env_http_headers']=extra
  # JSON scalar strings are also valid TOML scalar strings. The inline table
  # contains no secret; private values travel in the process environment.
  def toml(v):
   if isinstance(v,dict):return '{'+','.join(json.dumps(k)+'='+toml(x) for k,x in v.items())+'}'
   if isinstance(v,bool):return 'true' if v else 'false'
   return json.dumps(v,ensure_ascii=False)
  return ['-c','model_provider="ai_xs"','-c','model_providers.ai_xs='+toml(provider),'-c','model='+toml(p['models'][0])],env

 def model_list(self,engine,native):
  p=self.selected(engine)
  if engine=='codex':
   if native.get('data'):private_write(self.folder/'codex-model-catalog.json',native)
   catalog=read_json(self.folder/'codex-model-catalog.json') or read_json(Path(__file__).with_name('codex_model_catalog.json'))
   known={m['model']:m for m in catalog.get('data',[]) if m.get('model')}
   models=copy.deepcopy(native.get('data',[]));ids={m['model'] for m in models}
   for m in p['models']:
    if m not in ids:models.insert(0,copy.deepcopy(known.get(m)) or {'id':m,'model':m,'displayName':m,'inputModalities':['text','image'],'defaultReasoningEffort':'high','supportedReasoningEfforts':[{'reasoningEffort':x} for x in ['low','medium','high']]})
   for m in models:m['isDefault']=m['model']==p['models'][0]
   return {**native,'data':models}
  return {'data':[{'id':m,'model':m,'displayName':m,'isDefault':i==0,'inputModalities':['text','image'],'defaultReasoningEffort':'high','supportedReasoningEfforts':[{'reasoningEffort':x} for x in ['low','medium','high','max']]} for i,m in enumerate(p['models'])]}

 def discover(self,engine,params):
  p=self.candidate(engine,params);base=p['baseUrl'];headers=dict(p.get('headers',{}))
  # Identify the app rather than urllib; some API gateways reject Python's
  # default signature. Explicit provider headers retain precedence.
  if not any(name.lower()=='user-agent' for name in headers):headers['User-Agent']=USER_AGENT
  if not any(name.lower()=='accept' for name in headers):headers['Accept']='application/json'
  headers['Authorization']='Bearer '+p['key']
  if engine=='deepseek' and params.get('mode')=='official':url='https://api.deepseek.com/models'
  elif p['protocol']=='anthropic-messages':
   headers['x-api-key']=p['key'];headers['anthropic-version']='2023-06-01';url=base+('/models' if base.endswith('/v1') else '/v1/models')
  else:url=base+'/models'
  try:
   request=urllib.request.Request(url,headers=headers)
   with urllib.request.build_opener(NoRedirect()).open(request,timeout=20) as response:
    raw=response.read(2*1024*1024+1)
   if len(raw)>2*1024*1024:raise ValueError('模型列表响应过大')
   data=json.loads(raw)
   if not isinstance(data,dict):raise ValueError('接口未返回可识别的模型列表，请手动填写模型 ID')
   values=data.get('data',data.get('models',[]))
   if isinstance(values,dict):values=[dict(v,id=k) for k,v in values.items() if isinstance(v,dict)]
   ids=list(dict.fromkeys(m['id'] for m in values if isinstance(m,dict) and isinstance(m.get('id'),str)))
   if not ids:raise ValueError('接口未返回可识别的模型列表，请按服务商文档填写模型 ID')
   filtered=[m for m in ids if ('deepseek' in m.lower() if engine=='deepseek' else 'claude' in m.lower() if engine=='claude' else not any(x in m.lower() for x in ('claude','deepseek','gemini')))]
   return {'models':filtered or ids,'total':len(ids),'detail':'已读取模型列表；此检查不会调用模型生成回复。'}
  except urllib.error.HTTPError as e:
   with e:
    body=e.read(8192).decode('utf-8','replace').lower()
   if e.code==403 and any(marker in body for marker in ('cloudflare','cf-chl-','just a moment','browser_signature_banned')):
    raise ValueError('服务商的 Cloudflare / 防火墙拦截了 API 请求（HTTP 403），请联系服务商检查 API 放行规则。') from None
   if e.code==401:raise ValueError('API 密钥认证失败（HTTP 401），请检查密钥是否有效。') from None
   if e.code==403:raise ValueError('服务商拒绝访问模型列表（HTTP 403），请检查密钥权限、IP 限制或防火墙规则。') from None
   raise ValueError('模型列表请求失败（HTTP '+str(e.code)+'），请检查地址、密钥和协议；可手动填写模型 ID') from None
  except (OSError,ValueError) as e:
   if isinstance(e,ValueError) and not isinstance(e,json.JSONDecodeError):raise
   raise ValueError('无法读取模型列表，请检查连接；服务商也可能未提供模型列表接口') from None
