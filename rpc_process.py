"""Concurrent newline JSON-RPC transport for installed assistant processes."""
from __future__ import annotations
from collections import deque
import json
import os
import queue
import signal
import subprocess
import threading


class RpcProcess:
    def __init__(self, command, cwd, on_message=None, env=None):
        self.process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding='utf-8', bufsize=1, start_new_session=True,env=env)
        self.on_message = on_message
        self.pending = {}
        self.lock = threading.RLock()
        self.write_lock = threading.Lock()
        self.next_id = 0
        self.stderr = deque(maxlen=20)
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._errors, daemon=True).start()

    def _errors(self):
        try:
            for line in self.process.stderr: self.stderr.append(line[-1000:])
        except (OSError, ValueError): pass

    def write(self, message):
        with self.write_lock:
            if self.process.poll() is not None: raise RuntimeError('助手进程已退出')
            self.process.stdin.write(json.dumps(message, ensure_ascii=False)+'\n')
            self.process.stdin.flush()

    def request(self, method, params, timeout=45, on_sent=None):
        with self.lock:
            self.next_id += 1; ident = self.next_id
            result = queue.Queue(maxsize=1); self.pending[ident] = result
        try:
            self.write({'jsonrpc':'2.0','id':ident,'method':method,'params':params})
            if on_sent:on_sent()
            try: message = result.get(timeout=timeout)
            except queue.Empty as error: raise RuntimeError('助手接口超时：'+method) from error
            if 'error' in message: raise RuntimeError(str(message['error'].get('message', message['error'])))
            return message.get('result', {})
        finally:
            with self.lock: self.pending.pop(ident, None)

    def notify(self, method, params):
        self.write({'jsonrpc':'2.0','method':method,'params':params})

    def _read(self):
        try:
            for line in self.process.stdout:
                try: message = json.loads(line)
                except ValueError: continue
                if 'method' in message:
                    if self.on_message: self.on_message(message)
                    elif 'id' in message:
                        self.write({'jsonrpc':'2.0','id':message['id'],'error':{'code':-32601,'message':'Unsupported client request'}})
                elif 'id' in message:
                    with self.lock: target = self.pending.get(message['id'])
                    if target: target.put_nowait(message)
        except (OSError, ValueError, RuntimeError, queue.Full): pass
        finally:
            with self.lock:
                for target in self.pending.values():
                    try: target.put_nowait({'error':{'message':''.join(self.stderr)[-1500:] or '助手进程连接已关闭'}})
                    except queue.Full: pass

    def close(self):
        if self.process.poll() is None:
            try: os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError: pass
            try: self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try: os.killpg(self.process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
                self.process.wait(timeout=5)
        for pipe in (self.process.stdin,self.process.stdout,self.process.stderr):
            try: pipe.close()
            except (OSError, ValueError): pass
