"""Isolated project snapshots and explicit patch integration. Never mutate the source on launch."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import hashlib
import json
import zipfile
from provider_manager import private_write


def git(cwd,*args):
    result=subprocess.run(['git','-C',str(cwd),*args],capture_output=True,timeout=60)
    if result.returncode:raise ValueError(result.stderr.decode(errors='replace')[:2000] or 'Git 操作失败')
    return result.stdout


def _git(cwd,*args,input=None,env=None):
    result=subprocess.run(['git','-C',str(cwd),*args],input=input,capture_output=True,timeout=60,env=env)
    if result.returncode:raise ValueError(result.stderr.decode(errors='replace')[:3000] or result.stdout.decode(errors='replace')[:3000] or 'Git 操作失败')
    return result.stdout


def _commit(source, tree, base):
    env={**os.environ,'GIT_AUTHOR_NAME':'Ai-XS','GIT_AUTHOR_EMAIL':'aixs@localhost',
         'GIT_COMMITTER_NAME':'Ai-XS','GIT_COMMITTER_EMAIL':'aixs@localhost'}
    return _git(source,'commit-tree',tree,'-p',base,input=b'Ai-XS isolated merge\n',env=env).decode().strip()


def _tree(source, base, patch=None, working=False):
    # A private index leaves the user's index, branch and working files untouched.
    with tempfile.TemporaryDirectory(prefix='aixs-index-') as temp:
        env={**os.environ,'GIT_INDEX_FILE':str(Path(temp)/'index')}
        _git(source,'read-tree',base,env=env)
        if patch and Path(patch).stat().st_size:_git(source,'apply','--cached','--binary',str(Path(patch).resolve()),env=env)
        if working:_git(source,'add','--all',env=env)
        return _git(source,'write-tree',env=env).decode().strip()


def merge_patches(source, base, patches, current=None):
    """Three-way merge complete member trees; shared ancestors are applied once."""
    tree=current or git(source,'rev-parse',base+'^{tree}').decode().strip();seen=set()
    for patch in patches:
        if not patch or not Path(patch).stat().st_size:continue
        digest=hashlib.sha256(Path(patch).read_bytes()).hexdigest()
        if digest in seen:continue
        seen.add(digest);other=_tree(source,base,patch)
        if tree==other:continue
        left=_commit(source,tree,base);right=_commit(source,other,base)
        result=_git(source,'merge-tree','--write-tree','--merge-base='+base,left,right)
        tree=result.decode().splitlines()[0]
    return tree


def prepare(source, destination, dependencies, base=None):
    source=Path(source).resolve();destination=Path(destination)
    if destination.resolve().is_relative_to(source):raise ValueError('请选择具体项目目录，工作区不能位于源目录内部')
    try:
        top=git(source,'rev-parse','--show-toplevel').decode().strip()
    except ValueError:top=''
    if top:
        if Path(top)!=source:raise ValueError('编程协作请选择 Git 仓库根目录')
        if git(source,'status','--porcelain'):raise ValueError('项目有未提交修改，请先保存到 Git，再创建隔离工作区')
        base=base or git(source,'rev-parse','HEAD').decode().strip()
        tree=merge_patches(source,base,dependencies)
        git(source,'worktree','add','--detach',str(destination),base)
        try:git(destination,'read-tree','--reset','-u',tree)
        except Exception:
            git(source,'worktree','remove','--force',str(destination));raise
        return {'kind':'git','source':str(source),'base':base,'path':str(destination)}
    if dependencies:raise ValueError('非 Git 项目请将依赖成果作为文本交接；编程合并需要 Git')
    total=0
    for root,dirs,files in os.walk(source,followlinks=False):
        dirs[:]=[d for d in dirs if d not in ('.git','.state','node_modules','.gradle','build','__pycache__')]
        for name in dirs+files:
            p=Path(root)/name
            if p.is_symlink():raise ValueError('非 Git 项目含符号链接，请移除链接后创建隔离快照')
        for name in files:
            p=Path(root)/name
            if not p.is_symlink():total+=p.stat().st_size
        if total>200*1024*1024:raise ValueError('项目快照超过 200 MiB，请改用已提交的 Git 仓库')
    shutil.copytree(source,destination,symlinks=True,ignore=shutil.ignore_patterns('.git','.state','node_modules','.gradle','build','__pycache__'))
    baseline=destination.parent/'snapshot-base.json';private_write(baseline,snapshot_manifest(destination))
    return {'kind':'snapshot','source':str(source),'path':str(destination),'snapshotBase':str(baseline)}


def snapshot_manifest(root):
    result={}
    for path in sorted(Path(root).rglob('*')):
        if path.is_symlink():raise ValueError('快照包含符号链接，不能安全交接')
        if path.is_file():result[path.relative_to(root).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def merge_snapshots(workspace, artifacts):
    """Merge disjoint file changes, identical ancestors and deletions without touching the source."""
    root=Path(workspace['path']);base=json.loads(Path(workspace['snapshotBase']).read_text());merged=dict(base)
    for artifact in artifacts:
        previous=artifact.get('workspace',{});archive=artifact.get('archive')
        if not archive:continue
        if previous.get('source')!=workspace['source'] or not previous.get('snapshotBase') or json.loads(Path(previous['snapshotBase']).read_text())!=base:
            raise ValueError('依赖快照基线不一致，请重新核对')
        if hashlib.sha256(Path(archive).read_bytes()).hexdigest()!=artifact.get('archiveSha256'):raise ValueError('依赖快照校验失败')
        with zipfile.ZipFile(archive) as source:
            incoming={name:hashlib.sha256(source.read(name)).hexdigest() for name in source.namelist()}
            for name in set(base)|set(incoming):
                path=Path(name)
                if path.is_absolute() or '..' in path.parts:raise ValueError('依赖快照路径无效')
                before=base.get(name);after=incoming.get(name)
                if before==after:continue
                if merged.get(name) not in (before,after):raise ValueError('依赖快照文件冲突：'+name)
                target=root/name
                if after is None:
                    target.unlink(missing_ok=True);merged.pop(name,None)
                else:
                    target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.read(name))
                    target.chmod((source.getinfo(name).external_attr>>16)&0o777 or 0o600);merged[name]=after


def capture(workspace, folder):
    if workspace['kind']!='git':
        archive=Path(folder)/'artifacts.zip';files=[];total=0;root=Path(workspace['path'])
        with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as output:
            for path in sorted(root.rglob('*')):
                if path.is_symlink():raise ValueError('成果包含符号链接，不能直接导出；工作区已保留')
                if not path.is_file():continue
                total+=path.stat().st_size
                if total>200*1024*1024:raise ValueError('成果超过 200 MiB，工作区已保留，请手动处理')
                relative=path.relative_to(root).as_posix();files.append(relative);output.write(path,relative)
        archive.chmod(0o600)
        return {'workspace':workspace,'patch':'','archive':str(archive),'archiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),
                'files':files,'integration':'独立快照成果，已导出 ZIP，尚未写入原项目'}
    cwd=workspace['path']
    git(cwd,'add','--intent-to-add','--all')
    patch=Path(folder)/'changes.patch';patch.write_bytes(git(cwd,'diff','--binary',workspace['base']));patch.chmod(0o600)
    return {'workspace':workspace,'patch':str(patch),'changedFiles':git(cwd,'diff','--name-only',workspace['base']).decode().splitlines(),
            'integration':'尚未合入原项目'}


def integrate(artifact, ledger=None):
    w=artifact['workspace'];source=w['source']
    if w['kind']!='git':raise ValueError('快照成果请先查看，再手动复制所需文件')
    if ledger is None:
        if git(source,'status','--porcelain') or git(source,'rev-parse','HEAD').decode().strip()!=w['base']:
            raise ValueError('原项目基线已变化，请先检查补丁；未覆盖任何文件')
        patch=artifact['patch']
        if not Path(patch).stat().st_size:return '没有文件修改'
        git(source,'apply','--check','--binary',patch);git(source,'apply','--binary',patch)
        return '补丁已应用到原项目（未提交），请检查并测试'
    integrate_many([artifact],ledger,apply=True)
    return '补丁已应用到原项目（未提交），请检查并测试'


def integrate_many(artifacts, ledger, apply=False):
    if not artifacts:raise ValueError('请选择至少一个有 Git 成果的任务')
    first=artifacts[0]['workspace'];source=first['source'];base=first['base']
    if any(a['workspace'].get('kind')!='git' or a['workspace']['source']!=source or a['workspace']['base']!=base for a in artifacts):
        raise ValueError('成果必须来自同一项目及同一 Git 基线')
    if git(source,'rev-parse','HEAD').decode().strip()!=base:raise ValueError('原项目基线已变化，请重新核对成果')
    if git(source,'diff','--cached','--name-only'):raise ValueError('原项目暂存区有改动，请先核对，未覆盖任何文件')
    ledger=Path(ledger);state=json.loads(ledger.read_text()) if ledger.exists() else {}
    current=_tree(source,base,working=True)
    if state and (state.get('source')!=source or state.get('base')!=base):raise ValueError('合入记录与项目基线不匹配')
    expected=state.get('expectedTree') or git(source,'rev-parse',base+'^{tree}').decode().strip()
    if current!=expected:
        # Recover a crash after applying files but before persisting the receipt.
        if current!=state.get('pendingTree'):raise ValueError('原项目存在用户新增改动或未提交修改，请先核对；未覆盖任何文件')
    tree=merge_patches(source,base,[a['patch'] for a in artifacts],current)
    patch=git(source,'diff','--binary',current,tree)
    digest=hashlib.sha256(patch).hexdigest()
    result={'source':source,'base':base,'beforeTree':current,'afterTree':tree,'patchSha256':digest,
            'changedFiles':git(source,'diff','--name-only',current,tree).decode().splitlines(),'conflicts':[],
            'status':'unchanged' if current==tree else 'ready','applied':False}
    if not apply:return dict(result,patch=patch.decode(errors='replace')[:131072],patchTruncated=len(patch)>131072)
    if patch:
        _git(source,'apply','--check','--binary','-',input=patch)
        state.update(source=source,base=base,expectedTree=current,pendingTree=tree)
        private_write(ledger,state)
        _git(source,'apply','--binary','-',input=patch)
    state.update(source=source,base=base,expectedTree=tree,pendingTree=None,lastIntegration=result)
    private_write(ledger,state)
    return dict(result,applied=True,status='applied' if patch else 'unchanged')


def cleanup(workspace):
    path=Path(workspace['path'])
    if not path.exists():return
    if workspace['kind']=='git':git(workspace['source'],'worktree','remove','--force',str(path))
    else:shutil.rmtree(path)


def restore_snapshot(artifact, destination):
    archive=artifact.get('archive');destination=Path(destination)
    if not archive:return
    if hashlib.sha256(Path(archive).read_bytes()).hexdigest()!=artifact.get('archiveSha256'):raise ValueError('上次快照成果校验失败，不能继续')
    with zipfile.ZipFile(archive) as source:
        entries=source.infolist()
        if sum(e.file_size for e in entries)>200*1024*1024:raise ValueError('快照成果过大')
        for entry in entries:
            path=Path(entry.filename)
            if path.is_absolute() or '..' in path.parts or ((entry.external_attr>>16)&0o170000)==0o120000:raise ValueError('快照成果路径无效')
        # The new private snapshot is disposable; replace it so removed files stay removed.
        shutil.rmtree(destination);destination.mkdir();source.extractall(destination)
        for entry in entries:
            if not entry.is_dir():(destination/entry.filename).chmod((entry.external_attr>>16)&0o777 or 0o600)
