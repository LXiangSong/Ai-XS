"""Isolated project snapshots and explicit patch integration. Never mutate the source on launch."""
from pathlib import Path
import os
import shutil
import subprocess


def git(cwd,*args):
    result=subprocess.run(['git','-C',str(cwd),*args],capture_output=True,timeout=60)
    if result.returncode:raise ValueError(result.stderr.decode(errors='replace')[:2000] or 'Git 操作失败')
    return result.stdout


def prepare(source, destination, dependencies):
    source=Path(source).resolve();destination=Path(destination)
    if destination.resolve().is_relative_to(source):raise ValueError('请选择具体项目目录，工作区不能位于源目录内部')
    try:
        top=git(source,'rev-parse','--show-toplevel').decode().strip()
    except ValueError:top=''
    if top:
        if Path(top)!=source:raise ValueError('编程协作请选择 Git 仓库根目录')
        if git(source,'status','--porcelain'):raise ValueError('项目有未提交修改，请先保存到 Git，再创建隔离工作区')
        base=git(source,'rev-parse','HEAD').decode().strip()
        git(source,'worktree','add','--detach',str(destination),base)
        # Dependency patches include their ancestors. Deduplicate exact patch hashes.
        for patch in dependencies:
            if patch and Path(patch).stat().st_size:git(destination,'apply','--binary',str(patch))
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
    return {'kind':'snapshot','source':str(source),'path':str(destination)}


def capture(workspace, folder):
    if workspace['kind']!='git':return {'workspace':workspace,'patch':'','integration':'独立快照成果，尚未写入原项目'}
    cwd=workspace['path']
    git(cwd,'add','--intent-to-add','--all')
    patch=Path(folder)/'changes.patch';patch.write_bytes(git(cwd,'diff','--binary',workspace['base']));patch.chmod(0o600)
    return {'workspace':workspace,'patch':str(patch),'changedFiles':git(cwd,'diff','--name-only',workspace['base']).decode().splitlines(),
            'integration':'尚未合入原项目'}


def integrate(artifact):
    w=artifact['workspace'];source=w['source']
    if w['kind']!='git':raise ValueError('快照成果请先查看，再手动复制所需文件')
    if git(source,'status','--porcelain') or git(source,'rev-parse','HEAD').decode().strip()!=w['base']:
        raise ValueError('原项目基线已变化，请先检查补丁；未覆盖任何文件')
    patch=artifact['patch']
    if not Path(patch).stat().st_size:return '没有文件修改'
    git(source,'apply','--check','--binary',patch);git(source,'apply','--binary',patch)
    return '补丁已应用到原项目（未提交），请检查并测试'
