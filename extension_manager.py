"""GitHub extension library, with explicit per-Agent compatibility and assignment."""
from __future__ import annotations
import copy
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import threading
import time
import urllib.parse
import urllib.request
import uuid
import zipfile

AGENTS = ('codex', 'claude', 'deepseek')
LABELS = {'codex':'Codex', 'claude':'Claude Code', 'deepseek':'DeepSeek'}


def github_source(url, ref='', subdir=''):
    value = str(url).strip()
    if re.fullmatch(r'[\w.-]+/[\w.-]+', value): value = 'https://github.com/' + value
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != 'https' or parsed.hostname != 'github.com' or parsed.username or parsed.password or parsed.port or parsed.query or parsed.fragment:
        raise ValueError('请填写 GitHub HTTPS 仓库或目录链接')
    parts = [urllib.parse.unquote(p) for p in parsed.path.strip('/').split('/')]
    if len(parts) < 2 or not all(re.fullmatch(r'[\w.-]+', p) for p in parts[:2]):
        raise ValueError('GitHub 地址缺少仓库名称')
    owner, repo = parts[:2]; repo = repo.removesuffix('.git')
    if len(parts) > 2:
        if parts[2] not in ('tree','blob') or len(parts) < 4: raise ValueError('请使用仓库、tree 目录或 SKILL.md 文件链接')
        ref = ref or parts[3]
        subdir = subdir or '/'.join(parts[4:])
        if parts[2] == 'blob' and subdir.endswith('SKILL.md'): subdir = subdir[:-8].rstrip('/')
    path = PurePosixPath(subdir or '.')
    if path.is_absolute() or '..' in path.parts or '\\' in str(path): raise ValueError('仓库子目录无效')
    if ref and (len(ref) > 200 or any(c in ref for c in '\r\n\x00')): raise ValueError('分支或版本无效')
    return {'url':f'https://github.com/{owner}/{repo}', 'owner':owner, 'repo':repo, 'ref':ref, 'subdir':str(path)}


def fetch(url, limit):
    request = urllib.request.Request(url, headers={'User-Agent':'Ai-XS/0.8.4','Accept':'application/vnd.github+json'})
    with urllib.request.urlopen(request, timeout=35) as response:
        value = response.read(limit + 1)
    if len(value) > limit: raise ValueError('仓库下载超过 32 MiB，请选择较小的扩展仓库')
    return value


def unpack(payload, destination, repository=False):
    total = 0
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        entries = archive.infolist()
        if not entries or len(entries) > 6000: raise ValueError('仓库文件数量超出限制')
        for item in entries:
            path = PurePosixPath(item.filename)
            if path.is_absolute() or '..' in path.parts or '\\' in item.filename or not path.parts:
                raise ValueError('仓库压缩包包含无效路径')
            mode = item.external_attr >> 16
            if stat.S_ISLNK(mode): raise ValueError('仓库包含符号链接，请使用不含链接的扩展目录')
            total += item.file_size
            if total > 128 * 1024 * 1024 or item.file_size > 16 * 1024 * 1024: raise ValueError('解压后的仓库过大')
        for item in entries:
            target = destination.joinpath(*PurePosixPath(item.filename).parts)
            if item.is_dir(): target.mkdir(parents=True, exist_ok=True); continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(item) as source, target.open('wb') as out: shutil.copyfileobj(source, out)
            target.chmod(0o700 if item.external_attr >> 16 & 0o111 else 0o600)
    roots = list(destination.iterdir())
    if len(roots) == 1 and roots[0].is_dir(): return roots[0]
    if repository: raise ValueError('仓库压缩包结构无效')
    return destination


def metadata(path):
    text = path.read_text(encoding='utf-8')
    if len(text.encode()) > 256 * 1024: raise ValueError('SKILL.md 过大')
    front = text.split('---',2)[1] if text.startswith('---') and text.count('---') >= 2 else ''
    def field(name, default):
        match = re.search(r'^' + name + r':\s*(.+)$', front, re.M)
        return match.group(1).strip(' \"\'') if match else default
    return {'name':field('name',path.parent.name), 'description':field('description','')}


class ExtensionManager:
    def __init__(self, folder, home=None, downloader=fetch):
        self.folder = Path(folder) / 'extensions'; self.folder.mkdir(parents=True, exist_ok=True)
        self.path = self.folder / 'library.json'
        self.home = Path(home or os.environ.get('AI_XS_ENGINE_HOME') or Path.home())
        self.download = downloader; self.lock = threading.RLock()

    def _read(self):
        return json.loads(self.path.read_text()) if self.path.exists() else {}

    def _write(self, values):
        temp = self.path.with_suffix('.tmp'); temp.write_text(json.dumps(values,ensure_ascii=False,indent=2)); temp.chmod(0o600); temp.replace(self.path)

    @staticmethod
    def _inside(root, value):
        path = (root / value).resolve()
        if not path.is_relative_to(root.resolve()): raise ValueError('插件组件超出扩展目录')
        return path

    def _candidate(self, root, base, kind):
        relative = str(root.relative_to(base))
        result = {'id':hashlib.sha256((kind+':'+relative).encode()).hexdigest()[:16], 'relative':relative, 'kind':kind,
                  'skills':[], 'mcp':{}, 'components':[], 'nativeClaude':False}
        if kind == 'skill':
            result.update(metadata(root/'SKILL.md')); result['skills'] = ['SKILL.md']; result['components'] = ['Skills']
        else:
            manifests = [p for p in [root/'.claude-plugin/plugin.json',root/'.codex-plugin/plugin.json'] if p.is_file()]
            manifest = json.loads(manifests[0].read_text())
            if not isinstance(manifest,dict): raise ValueError('插件清单必须是 JSON 对象')
            result.update(name=str(manifest.get('name') or root.name), description=str(manifest.get('description') or ''))
            result['nativeClaude'] = (root/'.claude-plugin/plugin.json').is_file()
            # Native hooks, commands, agents and LSP cannot be silently treated as portable Skills.
            unsupported = []
            for name in ('hooks','commands','agents','lspServers','outputStyles','apps','connectors','dependencies','userConfig'):
                if manifest.get(name) or (root/name).exists() or (name=='lspServers' and (root/'.lsp.json').exists()): unsupported.append(name)
            skill_dirs = manifest.get('skills', ['skills'])
            if isinstance(skill_dirs,str): skill_dirs = [skill_dirs]
            if not isinstance(skill_dirs,list): raise ValueError('插件 skills 必须是目录路径')
            for value in skill_dirs:
                target = self._inside(root,str(value))
                paths = [target] if target.name == 'SKILL.md' and target.is_file() else sorted(target.rglob('SKILL.md')) if target.is_dir() else []
                for path in paths:
                    metadata(path); result['skills'].append(str(path.relative_to(root)))
            if result['skills']: result['components'].append('Skills')
            mcp = manifest.get('mcpServers')
            if isinstance(mcp,str): mcp = json.loads(self._inside(root,mcp).read_text())
            elif mcp is None and (root/'.mcp.json').is_file(): mcp = json.loads((root/'.mcp.json').read_text())
            if mcp:
                if not isinstance(mcp,dict): raise ValueError('MCP 配置必须是对象')
                mcp = mcp.get('mcpServers',mcp)
                for name, config in mcp.items():
                    if not re.fullmatch(r'[a-zA-Z0-9_-]+',name) or not isinstance(config,dict): raise ValueError('插件 MCP 配置无效')
                    if bool(config.get('url')) == bool(config.get('command')): raise ValueError('插件 MCP 需要 HTTP 地址或 stdio 命令')
                    if config.get('url') and (not isinstance(config['url'],str) or not re.match(r'^https?://',config['url'])): raise ValueError('插件 MCP 地址必须使用 HTTP 或 HTTPS')
                    if config.get('command') and not isinstance(config['command'],str): raise ValueError('插件 MCP command 必须是字符串')
                    if 'args' in config and (not isinstance(config['args'],list) or not all(isinstance(x,str) for x in config['args'])): raise ValueError('插件 MCP args 必须是字符串数组')
                    for field in ('headers','http_headers','env','env_http_headers'):
                        if field in config and (not isinstance(config[field],dict) or not all(isinstance(k,str) and isinstance(v,str) for k,v in config[field].items())): raise ValueError('插件 MCP '+field+' 必须是字符串映射')
                    if config.get('type') not in (None,'http','stdio'): unsupported.append('MCP '+str(config['type']))
                    variables = re.findall(r'\$\{[^}]+\}',json.dumps(config))
                    if any(v not in ('${CLAUDE_PLUGIN_ROOT}','${CODEX_PLUGIN_ROOT}') for v in variables): unsupported.append('需要额外变量配置的 MCP')
                    result['mcp'][name] = config
                result['components'].append('MCP')
            result['components'] += unsupported
            result['unsupported'] = unsupported
        compatible = {}
        for engine in AGENTS:
            native = kind == 'plugin' and engine == 'claude' and result['nativeClaude']
            unsupported = result.get('unsupported',[])
            supported = native or not unsupported and bool(result['skills'] or result['mcp'])
            compatible[engine] = {'supported':bool(supported), 'mode':'native' if native else 'shared',
                'reason':('使用 Claude 原生插件加载器' if native else '通过 Ai-XS 加载 Skills / MCP' if supported
                    else '当前适配器不能加载：'+ '、'.join(unsupported or ['未识别的插件组件']))}
        result['compatibility'] = compatible
        return result

    def _catalog(self, base, repo):
        plugin_roots = sorted({p.parent.parent for name in ('.claude-plugin/plugin.json','.codex-plugin/plugin.json') for p in base.rglob(name)})
        candidates = [self._candidate(p,repo,'plugin') for p in plugin_roots]
        for skill in sorted(base.rglob('SKILL.md')):
            if any(skill.is_relative_to(p) for p in plugin_roots): continue
            candidates.append(self._candidate(skill.parent,repo,'skill'))
        if len(candidates) > 200: raise ValueError('扩展数量过多，请选择具体子目录')
        if not candidates: raise ValueError('未找到 SKILL.md 或 .claude-plugin / .codex-plugin/plugin.json；DeepSeek 的 npm bundle 不能当作通用插件导入')
        return candidates

    def _workspace(self):
        previews = self.folder/'previews'; previews.mkdir(exist_ok=True)
        with self.lock:
            for old in previews.iterdir():
                if old.is_dir() and time.time()-old.stat().st_mtime > 86400: shutil.rmtree(old,ignore_errors=True)
            pending=sorted((p for p in previews.iterdir() if p.is_dir()),key=lambda p:p.stat().st_mtime)
            for old in pending[:-11]:shutil.rmtree(old,ignore_errors=True)
            token = uuid.uuid4().hex; workspace = previews/token; workspace.mkdir()
        return token, workspace

    @staticmethod
    def _preview_result(token, workspace, repo, base, source, payload, candidates):
        document = {'source':source,'repo':str(repo),'data':candidates,'sha256':hashlib.sha256(payload).hexdigest(),'createdAt':time.time()}
        (workspace/'preview.json').write_text(json.dumps(document,ensure_ascii=False))
        return {'previewId':token,'source':source,'data':copy.deepcopy(candidates)}

    def preview(self, params):
        source = github_source(params.get('url',''), str(params.get('ref') or ''), str(params.get('subdir') or ''))
        if not source['ref']:
            info = json.loads(self.download(f"https://api.github.com/repos/{source['owner']}/{source['repo']}",1024*1024))
            source['ref'] = info['default_branch']
        token, workspace = self._workspace()
        try:
            url = f"https://codeload.github.com/{source['owner']}/{source['repo']}/zip/"+urllib.parse.quote(source['ref'],safe='')
            payload = self.download(url,32*1024*1024)
            repo = unpack(payload,workspace/'repo',repository=True)
            base = self._inside(repo,source['subdir'])
            if not base.is_dir(): raise ValueError('仓库目录不存在，请检查分支和子目录')
            return self._preview_result(token,workspace,repo,base,source,payload,self._catalog(base,repo))
        except Exception:
            shutil.rmtree(workspace,ignore_errors=True); raise

    def import_local(self, params):
        path = Path(str(params.get('path','')))
        if not path.is_absolute() or not path.exists(): raise ValueError('请提供运行环境中已存在的绝对路径')
        token, workspace = self._workspace()
        try:
            if path.is_dir():
                if workspace.resolve().is_relative_to(path.resolve()): raise ValueError('请选择具体 Skill 或插件目录，不能导入整个环境或扩展管理目录')
                entries=[];size=0
                if path.is_symlink(): raise ValueError('本地目录包含符号链接')
                for entry in path.rglob('*'):
                    if entry.is_symlink(): raise ValueError('本地目录包含符号链接')
                    entries.append(entry)
                    if entry.is_file(): size+=entry.stat().st_size
                    if len(entries)>6000 or size>128*1024*1024: raise ValueError('本地扩展目录过大，请选择具体 Skill 或插件目录')
                repo=workspace/'package';shutil.copytree(path,repo)
                payload='\n'.join(str(p.relative_to(path)) for p in entries).encode()
            else:
                if path.is_symlink() or path.stat().st_size>32*1024*1024: raise ValueError('本地文件过大或为符号链接')
                payload=path.read_bytes()
                if zipfile.is_zipfile(io.BytesIO(payload)): repo=unpack(payload,workspace/'package')
                elif path.suffix.lower()=='.md':
                    repo=workspace/'package';repo.mkdir();(repo/'SKILL.md').write_bytes(payload)
                else: raise ValueError('请导入 ZIP 技能包或 SKILL.md 文件')
            source={'url':'本地导入：'+str(params.get('name') or path.name),'ref':'local','subdir':'.'}
            return self._preview_result(token,workspace,repo,repo,source,payload,self._catalog(repo,repo))
        except Exception:
            shutil.rmtree(workspace,ignore_errors=True);raise

    def _agents(self, requested, candidate):
        if not isinstance(requested,list) or not all(x in (*AGENTS,'all') for x in requested): raise ValueError('请选择有效的 Agent')
        selected = list(AGENTS) if 'all' in requested else list(dict.fromkeys(requested))
        incompatible = [LABELS[e]+'：'+candidate['compatibility'][e]['reason'] for e in selected if not candidate['compatibility'][e]['supported']]
        if incompatible: raise ValueError('\n'.join(incompatible))
        return selected

    def install(self, params):
        token = str(params.get('previewId',''))
        if not re.fullmatch('[a-f0-9]{32}',token): raise ValueError('请先读取 GitHub 仓库')
        workspace = self.folder/'previews'/token
        if not (workspace/'preview.json').is_file(): raise ValueError('预览已过期，请重新读取扩展')
        doc = json.loads((workspace/'preview.json').read_text())
        if time.time()-doc['createdAt'] > 86400: raise ValueError('预览已过期，请重新读取仓库')
        candidate = next((x for x in doc['data'] if x['id'] == params.get('candidateId')),None)
        if not candidate: raise ValueError('未找到选择的扩展')
        agents = self._agents(params.get('agents',[]),candidate)
        if not agents: raise ValueError('至少选择一个可用 Agent')
        ident = hashlib.sha256((doc['source']['url']+':'+candidate['relative']+':'+candidate['kind']).encode()).hexdigest()[:20]
        with self.lock:
            values = self._read()
            if ident in values: raise ValueError('此扩展已安装，可在卡片中修改 Agent 分配')
            target = self.folder/'packages'/ident
            if target.exists(): shutil.rmtree(target)
            shutil.copytree(self._inside(Path(doc['repo']),candidate['relative']),target)
            item = dict(candidate,id=ident,path=str(target),source=doc['source'],sha256=doc['sha256'],agents=agents,installedAt=time.time())
            try:
                values[ident] = item; self._write(values)
            except Exception:
                shutil.rmtree(target,ignore_errors=True); raise
        return {'extension':self._view(item),'detail':'已安装；下一次任务加载选中的扩展。'}

    @staticmethod
    def _view(item):
        value = copy.deepcopy(item)
        for engine, status in value['compatibility'].items(): status['selected'] = engine in value.get('agents',[])
        return value

    def listing(self):
        with self.lock:
            rows=[self._view(x) for x in self._read().values()]
        for item in rows:
            warnings=[]
            for config in item['mcp'].values():
                command=config.get('command','').replace('${CLAUDE_PLUGIN_ROOT}',item['path']).replace('${CODEX_PLUGIN_ROOT}',item['path'])
                if command and not shutil.which(command):warnings.append('运行环境中未找到 '+command+'，请先安装该依赖')
            if not Path(item['path']).is_dir():warnings.append('扩展文件已缺失，请卸载后重新导入')
            elif any(not (Path(item['path'])/rel).is_file() for rel in item['skills']):warnings.append('Skill 文件已缺失，请卸载后重新导入')
            item['runtimeWarnings']=warnings
        return {'data':rows}

    def configure(self, params):
        with self.lock:
            values = self._read(); item = values.get(str(params.get('id')))
            if not item: raise ValueError('扩展不存在')
            item['agents'] = self._agents(params.get('agents',[]),item); self._write(values)
        return {'extension':self._view(item),'detail':'已更新；下一次任务加载。'}

    def remove(self, ident):
        with self.lock:
            values = self._read(); item = values.pop(str(ident),None)
            if not item: raise ValueError('扩展不存在')
            self._write(values)
            shutil.rmtree(self.folder/'packages'/str(ident),ignore_errors=True)
        return {'ok':True}

    def active(self, engine):
        with self.lock:
            return [x for x in self._read().values() if engine in x.get('agents',[]) and Path(x['path']).is_dir()]

    def instructions(self, engine):
        rows = []
        for item in self.active(engine):
            if engine == 'claude' and item.get('nativeClaude'): continue
            for rel in item['skills']:
                path = self._inside(Path(item['path']),rel)
                if not path.is_file():continue
                info = metadata(path)
                rows.append(f"- {info['name']}：{info['description']}（{path}）")
        if not rows: return ''
        return 'Ai-XS 为当前 Agent 启用的 Skills（需要时先读取对应 SKILL.md，再按其说明使用同目录资源）：\n'+'\n'.join(rows)

    def claude_plugins(self):
        return [x['path'] for x in self.active('claude') if x.get('nativeClaude')]

    def mcp_servers(self, engine):
        result = {}
        for item in self.active(engine):
            if engine == 'claude' and item.get('nativeClaude'): continue
            root = item['path']
            for name, config in item['mcp'].items():
                value = json.loads(json.dumps(config).replace('${CLAUDE_PLUGIN_ROOT}',root).replace('${CODEX_PLUGIN_ROOT}',root))
                unsupported = re.findall(r'\$\{[^}]+\}',json.dumps(value))
                if unsupported: continue
                if 'headers' in value: value['http_headers'] = value.pop('headers')
                value.pop('type',None)
                result['xs_'+item['id'][:8]+'_'+name] = value
        return result
