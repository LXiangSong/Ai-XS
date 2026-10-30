"""Bounded, isolated subprocess workers with namespaced events and confirmed teardown."""
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import threading
import time
from provider_manager import private_write


def process_identity(pid):
    try:
        fields=Path(f'/proc/{int(pid)}/stat').read_text().rsplit(')',1)[1].split()
        return fields[19] if fields[0]!='Z' else None
    except (OSError,ValueError):return None


class WorkerPool:
    def __init__(self, folder, profiles, mcp, extensions, emit):
        self.folder=Path(folder);self.profiles=profiles;self.mcp=mcp;self.extensions=extensions
        self.emit=emit;self.lock=threading.RLock();self.live={};self.closed=False

    def run(self, attempt, participant, cwd, prompt, resources, callback, registered):
        folder=self.folder/attempt;folder.mkdir(parents=True,exist_ok=True,mode=0o700)
        config=self.profiles.resolve(participant,folder)
        servers=self.mcp.effective(config['engine'])
        built=servers.get('aixs-device')
        if built:
            from mcp_manager import McpManager
            resolved=McpManager.claude_servers({'device':built})['device']
            private_write(folder/'device-proxy.json',{'url':resolved['url'],'headers':resolved.get('headers',{}),
                'device':resources.get('device',False),'browser':resources.get('browser',False),
                'context':resources['context']})
            servers['aixs-device']={'command':sys.executable,'args':[str(Path(__file__).with_name('collaboration_mcp.py')),str(folder/'device-proxy.json')]}
        private_write(folder/'mcp-servers.json',servers)
        instructions='';plugins=[];extensionVersions=[]
        if self.extensions:
            with self.extensions.lock:
                instructions=self.extensions.instructions(config['engine'])
                for extension in self.extensions.active(config['engine']):
                    source=Path(extension['path']);target=folder/'extension-snapshots'/extension['id']
                    shutil.copytree(source,target,symlinks=True)
                    old=str(source);new=str(target);instructions=instructions.replace(old,new)
                    servers=json.loads(json.dumps(servers).replace(old,new))
                    extensionVersions.append({'id':extension['id'],'name':extension['name'],'source':extension.get('source',{})})
                    if config['engine']=='claude' and extension.get('nativeClaude'):plugins.append(new)
            private_write(folder/'mcp-servers.json',servers)
        config['extensions']=extensionVersions
        selected=json.loads((folder/'providers.json').read_text())['engines'][config['engine']]['profiles'][config['apiMode']]
        secrets=[v for v in [selected.get('key',''),*selected.get('headers',{}).values()] if v]
        for server in servers.values():secrets.extend(v for v in server.get('headers',{}).values() if v)
        private_write(folder/'spec.json',{'config':config,'cwd':cwd,'prompt':prompt,
            'plugins':plugins,'instructions':instructions+'\n只在本次隔离工作区内工作，不修改原项目或其他执行目录。需要用户决定时使用提问工具。'})
        env={**os.environ,'AI_XS_STATE_DIR':str(folder)}
        with self.lock:
            if self.closed:raise InterruptedError('共享服务正在关闭，未启动执行实例')
            process=subprocess.Popen([sys.executable,'-u',str(Path(__file__).with_name('collaboration_worker.py')),str(folder)],
                stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8',env=env,start_new_session=True,bufsize=1)
            lock=threading.Lock();entry={'process':process,'lock':lock,'config':config,'folder':str(folder),'children':[],'stopping':False}
            self.live[attempt]=entry
        def redact(value):
            if isinstance(value,str):
                for secret in secrets:value=value.replace(secret,'[REDACTED]')
                return value[-64000:]
            if isinstance(value,list):return [redact(v) for v in value[-100:]]
            if isinstance(value,dict):return {k:redact(v) for k,v in value.items()}
            return value
        def stderr():
            with (folder/'debug.log').open('a') as log:
                for line in process.stderr:
                    for secret in secrets:line=line.replace(secret,'[REDACTED]')
                    if log.tell()>1024*1024:log.seek(0);log.truncate()
                    log.write(line[-6000:]);log.flush()
        threading.Thread(target=stderr,daemon=True).start()
        result=None
        try:
            registered(config,{'pid':process.pid,'identity':process_identity(process.pid),'folder':str(folder)})
            with (folder/'events.jsonl').open('a') as log:
                for line in process.stdout:
                    try:message=redact(json.loads(line))
                    except ValueError:continue
                    if log.tell()>1024*1024:log.seek(0);log.truncate()
                    log.write(json.dumps(message,ensure_ascii=False)+'\n');log.flush()
                    if 'event' in message:
                        event=message['event'];event.update(resources['context']);event.update(workerId=attempt,attemptId=attempt)
                        if event.get('type')=='approval':event['nativeId']=str(event['id']);event['id']=attempt+':'+str(event['id'])
                        self.emit(event);callback('event',event)
                    if 'children' in message:
                        entry['children']=message['children'];callback('identity',{'children':message['children']})
                    if 'finished' in message:result=message['finished']
            process.wait(timeout=15)
            if result is None:result={'status':'interrupted' if entry['stopping'] else 'failed','error':'执行实例退出，未返回完整结果；请检查产物'}
        finally:
            # The worker normally closes every CLI group itself. Verify recorded
            # children too, including an abrupt worker crash, before releasing leases.
            certain=True
            for child in entry['children']:
                if child.get('identity') and process_identity(child['pid'])==child['identity']:
                    try:os.killpg(child['pid'],signal.SIGTERM)
                    except ProcessLookupError:pass
                    until=time.monotonic()+4
                    while process_identity(child['pid'])==child['identity'] and time.monotonic()<until:time.sleep(.05)
                    if process_identity(child['pid'])==child['identity']:
                        try:os.killpg(child['pid'],signal.SIGKILL)
                        except ProcessLookupError:pass
                        certain=False
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5);certain=False
            for pipe in (process.stdin,process.stdout,process.stderr):
                try:pipe.close()
                except (OSError,ValueError):pass
            with self.lock:self.live.pop(attempt,None)
            if not certain:result={'status':'needs_reconciliation','error':'子进程曾需要强制结束，请检查操作与文件状态后继续','partial':result}
        return result

    def control(self, attempt, method, params=None):
        with self.lock:entry=self.live.get(attempt)
        if not entry:raise ValueError('执行实例已经结束')
        if method=='stop':entry['stopping']=True
        with entry['lock']:
            entry['process'].stdin.write(json.dumps({'method':method,'params':params or {}})+'\n');entry['process'].stdin.flush()

    def close(self):
        with self.lock:self.closed=True;attempts=list(self.live)
        for attempt in attempts:
            try:self.control(attempt,'stop')
            except (ValueError,OSError):pass
