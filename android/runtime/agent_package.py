"""Versioned agent installation inside Ai-XS's shared Linux runtime."""
from __future__ import annotations
import argparse
import base64
import urllib.request
import urllib.parse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import uuid
import zipfile

AGENTS = {'codex':('codex','@openai/codex'), 'claude':('claude','@anthropic-ai/claude-code-linux-arm64'), 'deepseek':('dsh','@deepseek-ai/dsh')}
MAX_BYTES = 2 * 1024**3


def validate(agent, version):
    if agent not in AGENTS or not isinstance(version,str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.+_-]{0,79}',version):
        raise ValueError('Agent 或版本格式无效')


def check(prefix, agent, expected_version):
    binary=prefix/'bin'/AGENTS[agent][0]
    if not binary.is_file():raise ValueError('安装包缺少 Agent 启动程序')
    # Verify before activating. Existing releases and user state are untouched.
    result=subprocess.run([str(binary),'--version'],capture_output=True,text=True,timeout=90)
    if result.returncode:raise RuntimeError('Agent 版本检查失败：'+(result.stderr or result.stdout)[-600:])
    if not re.search(r'(?<![A-Za-z0-9])'+re.escape(expected_version)+r'(?![A-Za-z0-9])',result.stdout):
        raise ValueError('启动程序报告的版本与安装包声明不一致')
    print(result.stdout.strip(),flush=True)
    return result.stdout.strip()


def release_path(base, agent, release_id):
    if agent not in AGENTS or not isinstance(release_id,str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.+_-]{0,100}',release_id):
        raise ValueError('无效的 Agent 版本目录')
    parent=base/agent/'releases';release=parent/release_id
    if parent.is_symlink() or (base/agent).is_symlink() or release.is_symlink() or not release.is_dir():
        raise ValueError('Agent 版本不存在或目录不安全')
    return release


def release_version(release):
    metadata=release/'.aixs-release.json'
    if metadata.is_file():
        value=json.loads(metadata.read_text())['version']
    else:
        # Ai-XS releases before the version manager encoded version + UUID.
        match=re.fullmatch(r'(.+)-[a-f0-9]{8}',release.name)
        if not match:raise ValueError('无法识别旧版本目录')
        value=match.group(1)
    validate('codex',value)
    return value


def versions(base, agent):
    if agent not in AGENTS:raise ValueError('无效的 Agent')
    folder=base/agent;current=folder/'current'
    metadata={}
    try:metadata=json.loads((folder/'installed.json').read_text())
    except (OSError,ValueError):pass
    selected=current.resolve(strict=False) if current.is_symlink() else None
    result=[]
    releases=folder/'releases'
    if releases.is_dir() and not releases.is_symlink():
        for path in releases.iterdir():
            try:
                release_path(base,agent,path.name)
                version=release_version(path)
                result.append({'id':path.name,'version':version,'current':path.resolve()==selected,
                    'installedAt':int(path.stat().st_mtime*1000),'available':(path/'bin'/AGENTS[agent][0]).is_file()})
            except (OSError,ValueError,KeyError):continue
    result.sort(key=lambda x:(not x['current'],-x['installedAt'],x['id']))
    return {'agent':agent,'currentVersion':metadata.get('version','未安装'),'data':result}


def activate_release(base, agent, release, version, launch_dir=None):
    current=base/agent/'current';temp=current.with_name('current.new')
    temp.unlink(missing_ok=True);temp.symlink_to(Path('releases')/release.name)
    launch=(launch_dir or Path('/usr/local/bin'))/AGENTS[agent][0]
    launch.parent.mkdir(parents=True,exist_ok=True)
    link=launch.with_name(launch.name+'.new');link.unlink(missing_ok=True)
    link.symlink_to(current/'bin'/AGENTS[agent][0])
    metadata=base/agent/'installed.json'
    metadata_temp=metadata.with_suffix('.tmp')
    metadata_temp.write_text(json.dumps({'agent':agent,'version':version,'release':release.name}))
    # All files are prepared before switching the executable pointer.
    link.replace(launch);temp.replace(current);metadata_temp.replace(metadata)


def promote(stage, base, agent, version):
    release=base/agent/'releases'/(version+'-'+uuid.uuid4().hex[:8])
    release.parent.mkdir(parents=True,exist_ok=True)
    (stage/'.aixs-release.json').write_text(json.dumps({'agent':agent,'version':version}))
    stage.rename(release)
    activate_release(base,agent,release,version)


def select_version(base, agent, release_id, launch_dir=None):
    release=release_path(base,agent,release_id);version=release_version(release)
    check(release,agent,version)
    activate_release(base,agent,release,version,launch_dir)
    print('已切换 '+agent+' 至 '+version+'，其他版本和用户数据已保留。',flush=True)


def remove_version(base, agent, release_id):
    release=release_path(base,agent,release_id)
    current=base/agent/'current'
    metadata={}
    try:metadata=json.loads((base/agent/'installed.json').read_text())
    except (OSError,ValueError):pass
    if current.resolve(strict=False)==release.resolve() or metadata.get('release')==release_id:
        raise ValueError('不能删除当前使用的版本，请先切换到其他版本')
    shutil.rmtree(release)
    print('已删除旧版本 '+release_id+'，当前版本和用户数据已保留。',flush=True)


def install_claude_native(stage, version):
    package=AGENTS['claude'][1]
    metadata='https://registry.npmjs.org/'+urllib.parse.quote(package,safe='')+'/'+version
    with urllib.request.urlopen(metadata,timeout=30) as response:
        document=json.loads(response.read(1024*1024))
    dist=document['dist'];url=dist['tarball'];integrity=dist['integrity']
    if not url.startswith('https://registry.npmjs.org/') or not integrity.startswith('sha512-'):
        raise ValueError('官方原生包下载来源或校验值无效')
    archive=stage/'native.tgz'
    subprocess.run(['curl','-fL','--connect-timeout','20','--max-time','900','--retry','2','--speed-limit','1024','--speed-time','90','-o',str(archive),url],check=True,timeout=1100)
    with archive.open('rb') as source:actual=base64.b64encode(hashlib.file_digest(source,'sha512').digest()).decode()
    if actual!=integrity.removeprefix('sha512-'):raise ValueError('Claude 官方包 SHA-512 校验失败')
    target=stage/'lib/node_modules'/package;target.mkdir(parents=True)
    with tarfile.open(archive) as source:
        members=[]
        for entry in source:
            if not entry.name.startswith('package/'):raise ValueError('官方包路径格式无效')
            entry.name=entry.name[len('package/'):]
            if entry.name:members.append(entry)
        source.extractall(target,members=members,filter='data')
    archive.unlink();native=target/'claude'
    if not native.is_file():raise ValueError('官方 ARM64 包缺少 Claude 启动程序')
    (stage/'bin').mkdir(exist_ok=True)
    (stage/'bin/claude').symlink_to(Path('../lib/node_modules')/package/'claude')
    native.chmod(0o755)


def populate_prefix(stage, agent, version):
    """Install an official package into a dedicated prefix, with no HOME files."""
    validate(agent,version)
    if agent=='claude':install_claude_native(stage,version)
    else:
        subprocess.run(['npm','install','--global','--prefix',str(stage),'--no-audit','--no-fund','--maxsockets=2',
            '--fetch-retries=2','--fetch-timeout=300000','--loglevel=http',AGENTS[agent][1]+'@'+version],check=True,timeout=1200)
    check(stage,agent,version)


def npm_install(agent, version, base):
    validate(agent,version)
    stage=base/agent/('.install-'+uuid.uuid4().hex);stage.mkdir(parents=True)
    try:
        populate_prefix(stage,agent,version)
        promote(stage,base,agent,version)
    finally:
        if stage.exists():shutil.rmtree(stage)


def bundle_manifest(package, agent):
    with zipfile.ZipFile(package) as source:
        entries=source.infolist()
        if sorted(e.filename for e in entries)!=['manifest.json','payload.tar.gz'] or any(e.flag_bits&1 for e in entries):
            raise ValueError('离线包应仅包含 manifest.json 和 payload.tar.gz')
        if source.getinfo('manifest.json').file_size>16384 or source.getinfo('payload.tar.gz').file_size>MAX_BYTES:
            raise ValueError('离线包体积超出限制')
        manifest=json.loads(source.read('manifest.json'))
        validate(manifest.get('agent'),manifest.get('version'))
        if manifest.get('format')!=1 or manifest['agent']!=agent or manifest.get('architecture')!='linux-arm64':
            raise ValueError('离线包与当前 Agent 或 ARM64 环境不匹配')
        if not isinstance(manifest.get('sha256'),str) or not re.fullmatch(r'[0-9a-f]{64}',manifest['sha256']):
            raise ValueError('离线包缺少 SHA-256 校验值')
        digest=hashlib.sha256()
        with source.open('payload.tar.gz') as payload:
            while chunk:=payload.read(1024*1024):digest.update(chunk)
        if digest.hexdigest()!=manifest['sha256']:raise ValueError('离线包校验失败')
        return manifest


def extract_payload(payload, destination):
    with tarfile.open(payload,'r:gz') as source:
        members=source.getmembers();total=0
        if len(members)>150000:raise ValueError('离线包文件数量超出限制')
        for entry in members:
            path=PurePosixPath(entry.name)
            if path.is_absolute() or '..' in path.parts or not(entry.isdir() or entry.isfile() or entry.issym()):
                raise ValueError('离线包包含不支持的路径或文件类型')
            if entry.issym() and (PurePosixPath(entry.linkname).is_absolute()):
                raise ValueError('离线包包含绝对符号链接')
            total+=entry.size
            if total>MAX_BYTES:raise ValueError('离线包解压大小超出限制')
        # Python's data filter also rejects links escaping the extraction root,
        # including escapes through symlinks created by earlier members.
        print('准备解压 '+str(len(members))+' 个文件，共 '+str(total//1024**2)+' MiB',flush=True)
        def progress():
            for index,entry in enumerate(members):
                if index and index%3000==0:print('解压进度：'+str(index)+' / '+str(len(members)),flush=True)
                yield entry
        source.extractall(destination,members=progress(),filter='data')


def import_bundle(package, agent, base):
    manifest=bundle_manifest(package,agent)
    stage=base/agent/('.install-'+uuid.uuid4().hex);stage.mkdir(parents=True)
    payload=stage.parent/(stage.name+'.tar.gz')
    try:
        with zipfile.ZipFile(package) as source,source.open('payload.tar.gz') as data,payload.open('wb') as out:
            shutil.copyfileobj(data,out)
        print('离线包哈希、架构和 Agent 校验通过',flush=True)
        extract_payload(payload,stage)
        check(stage,agent,manifest['version']);promote(stage,base,agent,manifest['version'])
    finally:
        payload.unlink(missing_ok=True)
        if stage.exists():shutil.rmtree(stage)


def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['npm','bundle','list','select','remove']);p.add_argument('agent',choices=AGENTS);p.add_argument('value',nargs='?');p.add_argument('--base',type=Path,default=Path('/opt/agents'))
    args=p.parse_args()
    if args.action=='npm':npm_install(args.agent,args.value,args.base)
    elif args.action=='bundle':import_bundle(Path(args.value),args.agent,args.base)
    elif args.action=='list':print(json.dumps(versions(args.base,args.agent),ensure_ascii=False))
    elif args.action=='select':select_version(args.base,args.agent,args.value)
    elif args.action=='remove':remove_version(args.base,args.agent,args.value)


if __name__=='__main__':main()
