"""Bounded, read-only project file inventory independent of agent/model/tool names.

Reports net changes during a turn, including shell/build outputs in non-Git
projects. It does not claim authorship when another process writes concurrently.
Symlink directories are never traversed; dependency/VCS/runtime stores are omitted.
"""
import difflib
import hashlib
import os
from pathlib import Path
import stat
import threading
import time

EXCLUDED={'.git','.hg','.svn','node_modules','.gradle','.cache','__pycache__','.venv','venv'}
MAX_ENTRIES=30000
MAX_SECONDS=1.5
MAX_HASH_BYTES=16*1024*1024
MAX_HASH_FILE=256*1024
MAX_REPORT=1000
MAX_DIFF_LINES=120
MAX_DIFF_FILES=200


def inventory(root, exclude=(), with_text=False):
    root=Path(root);files={};texts={};complete=True;omitted=set();stack=[root];entries=0
    deadline=time.monotonic()+MAX_SECONDS;hashed=0
    while stack:
        folder=stack.pop()
        try:
            with os.scandir(folder) as children:
                for entry in children:
                    entries+=1
                    if entries>MAX_ENTRIES or time.monotonic()>deadline:return files,False,sorted(omitted)
                    path=Path(entry.path)
                    if path in exclude:omitted.add(str(path.relative_to(root)));continue
                    try:
                        info=entry.stat(follow_symlinks=False)
                        if stat.S_ISDIR(info.st_mode):
                            if entry.name in EXCLUDED:omitted.add(str(path.relative_to(root)))
                            else:stack.append(path)
                        elif stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode):
                            fingerprint=(info.st_size,info.st_mtime_ns,info.st_ctime_ns,info.st_mode,info.st_ino)
                            digest=None
                            if stat.S_ISLNK(info.st_mode):digest=('link',os.readlink(path))
                            elif info.st_size<=MAX_HASH_FILE and hashed+info.st_size<=MAX_HASH_BYTES:
                                # O_NOFOLLOW also closes a path-replacement race.
                                fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
                                with os.fdopen(fd,'rb') as stream:
                                    raw=stream.read(MAX_HASH_FILE+1);hashed+=len(raw)
                                if len(raw)<=MAX_HASH_FILE:
                                    digest=hashlib.sha256(raw).hexdigest()
                                    if with_text and b'\x00' not in raw[:8192]:
                                        try:texts[str(path.relative_to(root))]=raw.decode('utf-8')
                                        except UnicodeDecodeError:pass
                            files[str(path.relative_to(root))]=(fingerprint,digest)
                    except OSError:complete=False
        except OSError:complete=False
    return (files,complete,sorted(omitted),texts) if with_text else (files,complete,sorted(omitted))


def line_diff(path, before, after, kind):
    """Line stats and a bounded unified diff for text files; None when unknown."""
    if kind=='created':
        if after is None:return None,None,None
        old_lines=[];new_lines=after.splitlines()
    elif kind=='deleted':
        if before is None:return None,None,None
        old_lines=before.splitlines();new_lines=[]
    else:
        if before is None or after is None:return None,None,None
        old_lines=before.splitlines();new_lines=after.splitlines()
    added=removed=0;diff=[]
    try:
        for line in difflib.unified_diff(old_lines,new_lines,fromfile='a/'+path,tofile='b/'+path,
                n=2,lineterm=''):
            if line.startswith('+++') or line.startswith('---'):continue
            if line.startswith('+'):added+=1
            elif line.startswith('-'):removed+=1
            if len(diff)<MAX_DIFF_LINES:diff.append(line)
    except Exception:
        return None,None,None
    if not diff and kind!='created' and kind!='deleted':return 0,0,None
    return added,removed,'\n'.join(diff)


class TurnFileReports:
    def __init__(self, exclude=()):
        self.roots={};self.active={};self.lock=threading.RLock()
        self.exclude=tuple(Path(p).resolve() for p in exclude)

    def bind(self, thread):
        if isinstance(thread,dict) and thread.get('id') and thread.get('cwd'):
            with self.lock:self.roots[thread['id']]=str(Path(thread['cwd']).resolve())

    def observe(self, event):
        method=event.get('method');params=event.get('params') or {};tid=params.get('threadId')
        if method=='thread/started':self.bind(params.get('thread'));return
        if method not in ('turn/started','turn/completed'):return
        turn=params.get('turn') or {};key=(tid,turn.get('id'))
        with self.lock:
            if method=='turn/started':
                if key in self.active:return
                root=params.get('cwd') or self.roots.get(tid)
                if not root:return
                root=str(Path(root).resolve())
                snapshot=inventory(root,self.exclude,with_text=True)
                before,complete,omitted=snapshot[:3]
                before_text=snapshot[3] if len(snapshot)>3 else {}
                self.active[key]=(root,before,complete,omitted,before_text)
                return
            tracked=self.active.pop(key,None)
        if not tracked:return
        root,before,complete,omitted,before_text=tracked
        snapshot=inventory(root,self.exclude,with_text=True)
        after,final_complete,final_omitted=snapshot[:3]
        after_text=snapshot[3] if len(snapshot)>3 else {}
        partial=not (complete and final_complete);changes=[]
        for name in sorted(before.keys()|after.keys()):
            old=before.get(name);new=after.get(name);kind=None
            if old is None:
                # A partial initial scan cannot prove a previously unseen file is new.
                if complete:kind='created'
            elif new is None:
                if final_complete:kind='deleted'
            elif (old[1]!=new[1] if old[1] is not None and new[1] is not None else old[0]!=new[0]):
                kind='modified'
            if kind is None:continue
            change={'path':name,'kind':kind,
                    'verification':'content' if old and new and old[1] is not None and new[1] is not None else 'filesystem'}
            added,removed,diff=line_diff(name,before_text.get(name),after_text.get(name),kind)
            if added is not None:
                change.update({'added':added,'removed':removed})
                if diff:change['diff']=diff
            changes.append(change)
        counts={kind:sum(c['kind']==kind for c in changes) for kind in ('created','modified','deleted')}
        total_added=sum(c.get('added',0) for c in changes);total_removed=sum(c.get('removed',0) for c in changes)
        displayed=changes[:MAX_REPORT];lines=[]
        for kind,title in (('created','新增 / 生成'),('modified','修改'),('deleted','删除')):
            paths=[c['path'] for c in displayed if c['kind']==kind]
            if paths:lines.append(title+'（'+str(len(paths))+'）\n'+'\n'.join(paths[:MAX_REPORT]))
        if not lines:lines=['已检查范围内未检测到文件变化' if partial else '本轮未检测到项目文件变化']
        if partial:lines.append('扫描达到限额或部分文件无法读取；清单可能不完整。')
        if len(changes)>MAX_REPORT:lines.append('文件较多，仅展示前 '+str(MAX_REPORT)+' 项。')
        ignored=sorted(set(omitted+final_omitted))
        if ignored:lines.append('未扫描依赖、缓存或内部目录：'+', '.join(ignored[:12])+(' 等' if len(ignored)>12 else ''))
        lines.append('范围：'+root+'；记录本轮期间的文件变化，包含命令生成文件。')
        report={'id':'aixs-file-report','type':'fileReport','status':'completed','root':root,
                'changes':changes[:MAX_REPORT],'counts':counts,'partial':partial or len(changes)>MAX_REPORT,
                'added':total_added,'removed':total_removed,
                'excluded':ignored[:100],'text':'\n\n'.join(lines),
                'progressMessage':'新增 '+str(counts['created'])+' · 修改 '+str(counts['modified'])+' · 删除 '+str(counts['deleted'])}
        items=turn.setdefault('items',[])
        items[:]=[i for i in items if i.get('id')!=report['id']]
        # Like Codex's patch cell: file changes sit before the final reply, not after it.
        at=len(items)
        for index in range(len(items)-1,-1,-1):
            if items[index].get('type')=='agentMessage':at=index;break
        items.insert(at,report)
