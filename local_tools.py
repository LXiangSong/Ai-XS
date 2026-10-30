"""Workspace files and commands remain available independently of either AI CLI."""
import base64
import os
from pathlib import Path
import shutil
import signal
import subprocess
import threading


class LocalTools:
    def __init__(self, emit):
        self.emit = emit
        self.lock = threading.RLock()
        self.processes = {}

    def call(self, method, params):
        if method == 'command/exec':return self.execute(params)
        if method == 'command/exec/terminate':
            with self.lock:process = self.processes.get(params.get('processId'))
            if not process:raise ValueError('命令已结束')
            self.terminate(process)
            return {}
        raw = params.get('path')
        if not isinstance(raw,str) or not Path(raw).is_absolute():raise ValueError('请使用绝对路径')
        path = Path(raw)
        if method == 'fs/readDirectory':
            return {'entries':[{'fileName':p.name,'isDirectory':p.is_dir(),'isFile':p.is_file()} for p in path.iterdir()]}
        if method == 'fs/getMetadata':
            stat = path.stat()
            return {'isDirectory':path.is_dir(),'isFile':path.is_file(),'size':stat.st_size}
        if method == 'fs/readFile':
            if path.stat().st_size > 3*1024*1024:raise ValueError('此文件较大，请使用终端编辑')
            return {'dataBase64':base64.b64encode(path.read_bytes()).decode()}
        if method == 'fs/writeFile':
            data = base64.b64decode(params.get('dataBase64',''),validate=True)
            if len(data)>3*1024*1024:raise ValueError('文件过大')
            path.write_bytes(data)
        elif method == 'fs/createDirectory':path.mkdir(parents=params.get('recursive') is True,exist_ok=params.get('recursive') is True)
        elif method == 'fs/remove':
            if path.is_dir() and not path.is_symlink():
                if params.get('recursive') is True:shutil.rmtree(path)
                else:path.rmdir()
            else:path.unlink(missing_ok=params.get('force') is True)
        else:raise ValueError('不支持的文件操作')
        return {}

    @staticmethod
    def terminate(process):
        try:os.killpg(process.pid,signal.SIGTERM)
        except ProcessLookupError:return
        try:process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            try:os.killpg(process.pid,signal.SIGKILL)
            except ProcessLookupError:pass

    def execute(self, params):
        command = params.get('command')
        if not isinstance(command,list) or not command or not all(isinstance(x,str) for x in command):raise ValueError('命令必须是字符串数组')
        timeout = params.get('timeoutMs',30000)
        if not isinstance(timeout,(int,float)) or not 0<timeout<=300000:raise ValueError('命令超时必须在 1 到 300000 毫秒之间')
        pid = params.get('processId') or os.urandom(16).hex()
        with self.lock:
            if pid in self.processes:raise ValueError('命令标识已存在')
            process = subprocess.Popen(command,cwd=params.get('cwd'),stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
            self.processes[pid] = process
        outputs = {'stdout':bytearray(),'stderr':bytearray()}
        def read(name, pipe):
            try:
                while chunk := os.read(pipe.fileno(),8192):
                    remaining = 2*1024*1024-len(outputs[name])
                    if remaining>0:outputs[name].extend(chunk[:remaining])
                    if params.get('streamStdoutStderr'):
                        self.emit({'type':'notification','method':'command/exec/outputDelta','params':{'processId':pid,'deltaBase64':base64.b64encode(chunk).decode()}})
            finally:pipe.close()
        readers = [threading.Thread(target=read,args=(name,pipe),daemon=True) for name,pipe in [('stdout',process.stdout),('stderr',process.stderr)]]
        for reader in readers:reader.start()
        timer = threading.Timer(timeout/1000,self.terminate,args=(process,));timer.daemon=True;timer.start()
        try:
            code = process.wait()
            for reader in readers:reader.join(timeout=3)
            return {'exitCode':code,**{name:data.decode('utf-8','replace') for name,data in outputs.items()}}
        finally:
            timer.cancel()
            with self.lock:self.processes.pop(pid,None)

    def close(self):
        with self.lock:processes = list(self.processes.values())
        for process in processes:self.terminate(process)
