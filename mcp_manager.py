"""Shared MCP configuration consumed by each assistant's native transport."""
from __future__ import annotations
import copy
import json
import os
from pathlib import Path
import re
import threading
import time
import tomllib
import urllib.request


def http_tools(config):
    headers = {'Content-Type':'application/json','Accept':'application/json, text/event-stream', **config.get('http_headers', {})}
    for key, env in config.get('env_http_headers', {}).items():
        if env in os.environ: headers[key] = os.environ[env]
    if config.get('bearer_token_env_var'):
        headers['Authorization'] = 'Bearer '+os.environ[config['bearer_token_env_var']]
    def request(method, params, ident=None):
        payload = {'jsonrpc':'2.0','method':method,'params':params}
        if ident is not None: payload['id'] = ident
        req = urllib.request.Request(config['url'], data=json.dumps(payload).encode(), headers=headers)
        with urllib.request.urlopen(req, timeout=15) as response:
            if response.headers.get('Mcp-Session-Id'): headers['Mcp-Session-Id'] = response.headers['Mcp-Session-Id']
            if response.status == 202 or ident is None: return {}
            if 'text/event-stream' in response.headers.get('Content-Type',''):
                data = []
                for raw in response:
                    line = raw.decode().rstrip('\r\n')
                    if line.startswith('data:'): data.append(line[5:].lstrip())
                    elif not line and data:
                        event = json.loads('\n'.join(data)); data = []
                        if event.get('id') == ident: break
                else: raise RuntimeError('MCP 未返回响应')
            else: event = json.load(response)
        if 'error' in event: raise RuntimeError(str(event['error'].get('message','MCP 请求失败')))
        return event.get('result',{})
    try:
        initialized = request('initialize', {'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'ai-xs-mcp-check','version':'1.0'}}, 1)
        headers['MCP-Protocol-Version'] = initialized['protocolVersion']
        request('notifications/initialized', {})
        tools = {}; cursor = None
        for index in range(20):
            response = request('tools/list', {'cursor':cursor} if cursor else {}, index+2)
            tools.update({tool['name']:tool for tool in response.get('tools',[])})
            cursor = response.get('nextCursor')
            if not cursor: break
        return tools
    finally:
        if 'Mcp-Session-Id' in headers:
            try:
                with urllib.request.urlopen(urllib.request.Request(config['url'],headers=headers,method='DELETE'),timeout=3): pass
            except OSError: pass


class McpManager:
    def __init__(self, app, folder):
        self.app = app
        self.path = Path(folder)/'mcp-servers.json'
        self.claude_path = Path(folder)/'claude-mcp.json'
        self.lock = threading.RLock(); self.cache = {}
        if not self.path.exists():
            try: servers = tomllib.loads((Path.home()/'.codex/config.toml').read_text()).get('mcp_servers',{})
            except (OSError,ValueError): servers = {}
            self._write(servers)
        else:
            self._write(self.read())

    def read(self):
        with self.lock:
            return json.loads(self.path.read_text())

    def _write(self, servers):
        for path, value in [(self.path,servers),(self.claude_path,{'mcpServers':self.claude_servers(servers)})]:
            temp = path.with_suffix('.tmp')
            temp.write_text(json.dumps(value,ensure_ascii=False)); temp.chmod(0o600); temp.replace(path)

    @staticmethod
    def claude_servers(servers):
        result = {}
        for name, config in servers.items():
            if config.get('enabled') is False: continue
            if config.get('url'):
                headers = dict(config.get('http_headers',{}))
                for key, env in config.get('env_http_headers',{}).items():
                    if env in os.environ: headers[key] = os.environ[env]
                if config.get('bearer_token_env_var') and config['bearer_token_env_var'] in os.environ:
                    headers['Authorization'] = 'Bearer '+os.environ[config['bearer_token_env_var']]
                result[name] = {'type':'http','url':config['url'],'headers':headers}
            else: result[name] = {key:config[key] for key in ('command','args','env','cwd') if key in config}
        return result

    def acp_servers(self):
        result = []
        for name, config in self.claude_servers(self.effective('deepseek')).items():
            if config.get('url'):
                result.append({'type':'http','name':name,'url':config['url'],
                    'headers':[{'name':k,'value':v} for k,v in config.get('headers',{}).items()]})
            else:
                result.append({'name':name,'command':config['command'],'args':config.get('args',[]),
                    'env':[{'name':k,'value':v} for k,v in config.get('env',{}).items()]})
        return result

    def effective(self, engine):
        servers = self.read()
        if getattr(self,'extensions',None): servers.update(self.extensions.mcp_servers(engine))
        return servers

    def prepare_claude(self):
        with self.lock:
            temp = self.claude_path.with_suffix('.tmp')
            temp.write_text(json.dumps({'mcpServers':self.claude_servers(self.effective('claude'))},ensure_ascii=False))
            temp.chmod(0o600); temp.replace(self.claude_path)

    def save(self, name, value):
        if not isinstance(name,str) or not re.fullmatch(r'[a-zA-Z0-9_-]+',name): raise ValueError('MCP 名称格式不正确')
        if not isinstance(value,dict) or bool(value.get('url')) == bool(value.get('command')): raise ValueError('请填写 HTTP 地址或 stdio 命令其中一项')
        if value.get('url') and (not isinstance(value['url'],str) or not re.match(r'^https?://',value['url'])): raise ValueError('MCP 地址需以 http:// 或 https:// 开头')
        for key in ('http_headers','env_http_headers','env'):
            if key in value and (not isinstance(value[key],dict) or not all(isinstance(k,str) and isinstance(v,str) for k,v in value[key].items())): raise ValueError(key+' 必须是字符串映射')
        if 'args' in value and (not isinstance(value['args'],list) or not all(isinstance(x,str) for x in value['args'])): raise ValueError('args 必须是字符串数组')
        if value.get('command') and not isinstance(value['command'],str): raise ValueError('command 必须是字符串')
        with self.lock:
            if self.app.process and self.app.process.poll() is None:
                self.app.call('config/value/write',{'keyPath':'mcp_servers.'+name,'value':value,'mergeStrategy':'replace'})
            servers = self.read(); servers[name] = copy.deepcopy(value); self._write(servers); self.cache.pop(name,None)
        return {'ok':True}

    def sync_codex(self):
        servers = self.effective('codex')
        signature = json.dumps(servers,sort_keys=True)
        if getattr(self,'codex_synced',None) == (self.app.process,signature): return
        previous = self.path.parent/'codex-extension-mcp.json'
        managed = json.loads(previous.read_text()) if previous.exists() else []
        for name in managed:
            if name not in servers:
                self.app.call('config/value/write',{'keyPath':'mcp_servers.'+name+'.enabled','value':False,'mergeStrategy':'replace'})
        for name,value in servers.items():
            self.app.call('config/value/write',{'keyPath':'mcp_servers.'+name,'value':value,'mergeStrategy':'replace'})
        temp=previous.with_suffix('.tmp');temp.write_text(json.dumps([name for name in servers if name.startswith('xs_')]))
        temp.chmod(0o600);temp.replace(previous)
        self.app.call('config/mcpServer/reload',{})
        self.codex_synced = (self.app.process,signature)

    def status(self, engine, refresh=False):
        configured = self.effective(engine)
        native = {}
        if engine == 'codex':
            try: native = {s['name']:s for s in self.app.call('mcpServerStatus/list',{}).get('data',[])}
            except RuntimeError: pass
        result = []
        for name, cfg in configured.items():
            if cfg.get('enabled') is False:
                result.append({'name':name,'authStatus':'已停用','tools':{}}); continue
            if name in native:
                result.append(native[name]); continue
            with self.lock: cached = self.cache.get(name)
            if refresh or not cached or time.monotonic()-cached[0]>30:
                try:
                    if not cfg.get('url'): value={'name':name,'tools':{},'authStatus':'已配置 · 任务启动时加载'}
                    else: value={'name':name,'tools':http_tools(cfg),'authStatus':'服务可连接 · 下次任务加载'}
                except (OSError,ValueError,RuntimeError,KeyError) as error:
                    value={'name':name,'tools':{},'authStatus':'连接失败','error':str(error)}
                with self.lock: self.cache[name]=(time.monotonic(),value)
            else: value=cached[1]
            result.append(copy.deepcopy(value))
        return {'data':result}

    def reload(self):
        with self.lock: self._write(self.read()); self.cache.clear()
        codex='Codex 下次启动加载'
        if self.app.process and self.app.process.poll() is None:
            self.app.call('config/mcpServer/reload',{});codex='Codex 已请求重新加载'
        return {'ok':True,'detail':codex+'；Claude 和 DeepSeek 在下次任务加载配置。'}
