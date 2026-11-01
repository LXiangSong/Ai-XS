"""Delete exact, app-owned session artifacts without following project links."""
import json
import os
from pathlib import Path
import shutil
import uuid


def remove_owned(root, path):
    root=Path(root).resolve();path=Path(path)
    if path==root or not path.parent.resolve().is_relative_to(root):
        raise ValueError('拒绝清理运行目录之外的文件')
    if not path.is_symlink() and (path.resolve()==root or not path.resolve().is_relative_to(root)):
        raise ValueError('拒绝清理运行目录之外的文件')
    if path.is_symlink():path.unlink()
    elif path.is_dir():shutil.rmtree(path)
    else:path.unlink(missing_ok=True)


def remove_history_entries(path, identities):
    path=Path(path)
    if not path.exists():return
    target=path.with_name('.'+path.name+'.delete-'+uuid.uuid4().hex)
    changed=False
    try:
        with path.open('rb') as source,target.open('xb') as output:
            target.chmod(0o600)
            for line in source:
                try:row=json.loads(line)
                except (ValueError,UnicodeError):row={}
                if isinstance(row,dict) and any(isinstance(row.get(key),str) and row[key] in identities for key in ('sessionId','session_id','threadId')):
                    changed=True;continue
                output.write(line)
        if changed:target.replace(path)
    finally:target.unlink(missing_ok=True)


def delete_claude(native_id, thread_id):
    uuid.UUID(native_id)
    root=Path(os.environ.get('CLAUDE_CONFIG_DIR') or Path.home()/'.claude')
    for project in (root/'projects').glob('*'):
        if not project.is_dir():continue
        remove_owned(root,project/(native_id+'.jsonl'))
        remove_owned(root,project/native_id)
    for name in ('tasks','file-history','session-env'):
        remove_owned(root,root/name/native_id)
    remove_owned(root,root/'debug'/(native_id+'.txt'))
    for path in (root/'todos').glob(native_id+'-*.json'):remove_owned(root,path)
    remove_history_entries(root/'history.jsonl',{native_id,thread_id})


def delete_deepseek(native_id, thread_id):
    if not native_id or Path(native_id).name!=native_id or native_id in ('.','..') or '\\' in native_id:
        raise ValueError('无效的 DeepSeek 原生会话 ID')
    root=Path(os.environ.get('DSH_HOME') or Path.home()/'.dsh')
    for project in (root/'sessions').glob('*'):
        if not project.is_dir():continue
        remove_owned(root,project/native_id)
    remove_history_entries(root/'history.jsonl',{native_id,thread_id})
