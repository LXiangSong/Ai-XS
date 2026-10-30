"""Manage only Ai-XS's local engine processes and their installed CLI packages."""
from __future__ import annotations
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
import uuid
from contextlib import contextmanager


class EngineManager:
    def __init__(self, app, claude, folder, deepseek=None):
        self.app, self.claude = app, claude
        self.bridges = {'claude':claude}
        if deepseek is not None:self.bridges['deepseek']=deepseek
        self.folder = Path(folder)
        self.folder.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.enabled = {engine:True for engine in ['codex',*self.bridges]}
        self.errors = {}
        self.versions = {}
        self.jobs = {}
        self.busy = {}
        self.inflight = {engine:0 for engine in self.enabled}
        self.closing = False
        self.single_agent = os.environ.get('AI_XS_SINGLE_AGENT') == '1'
        try:
            saved = json.loads((self.folder/'engine-services.json').read_text())
            self.enabled.update({key:bool(saved[key]) for key in self.enabled if key in saved})
        except (OSError, ValueError): pass
        try:
            self.jobs = json.loads((self.folder/'engine-jobs.json').read_text())
            for job in self.jobs.values():
                if job.get('status') in ('pending','running'):
                    job.update(status='failed', error='工作台重启，操作已中断；请刷新版本后检查', finishedAt=time.time())
        except (OSError, ValueError): self.jobs = {}

    def _save(self):
        for filename,value in [('engine-services.json',self.enabled),('engine-jobs.json',self.jobs)]:
            path = self.folder/filename; temp=path.with_suffix('.tmp')
            temp.write_text(json.dumps(value, ensure_ascii=False)); temp.replace(path)

    def boot(self):
        if self.enabled['codex'] and not self.single_agent:
            try: self.app.start()
            except Exception as error:
                self.errors['codex']=str(error); self.app.stop()

    def _validate(self, engine):
        if engine not in self.enabled: raise ValueError('未知的 AI 助手')

    def _version(self, engine, refresh=False):
        entry=self.versions.get(engine)
        if not refresh and entry and time.monotonic()-entry['at']<30:return entry['value']
        try:
            command=([shutil.which('codex') or 'codex'] if engine=='codex' else self.bridges[engine].command(str(Path.home())))+['--version']
            result=subprocess.run(command, capture_output=True, text=True, timeout=15)
            if result.returncode:raise RuntimeError((result.stderr or result.stdout).strip()[:1000] or '版本检查失败')
            value={'installed':True,'available':True,'version':result.stdout.strip()}
        except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
            value={'installed':False,'available':False,'version':None,'error':str(error)}
        self.versions[engine]={'at':time.monotonic(),'value':value}
        return value

    def _update_plan(self, engine):
        return 'Ai-XS 设置 → Agent 管理',None

    def status(self, refresh=False, probe=True):
        with self.lock:
            result=[]
            for engine,name in [('codex','Codex'),*[(key,{'claude':'Claude Code','deepseek':'DeepSeek'}[key]) for key in self.bridges]]:
                version=self._version(engine,refresh) if probe else self.versions.get(engine,{}).get('value',{})
                source,plan=self._update_plan(engine)
                process=self.app.process if engine=='codex' else None
                alive=bool(process and process.poll() is None)
                tasks=len(self.app.active_turns) if engine=='codex' else len(self.bridges[engine].runs)
                job=self.jobs.get(self.busy.get(engine))
                error=self.errors.get(engine) or version.get('error')
                state='updating' if job and job['action']=='update' else 'starting' if job and job['action']=='start' else 'stopping' if job else 'stopped' if not self.enabled[engine] else 'error' if error or engine=='codex' and not alive and not self.single_agent else 'running' if tasks else 'ready'
                latest=next((j for j in reversed(list(self.jobs.values())) if j['engine']==engine),None)
                result.append({**version,'id':engine,'name':name,'enabled':self.enabled[engine],'running':alive if engine=='codex' else bool(tasks),
                    'state':state,'activeTasks':tasks,'pid':process.pid if alive else None,'processCount':int(alive) if engine=='codex' else tasks,
                    'installSource':source,'updateSupported':plan is not None,'error':error,'operation':{**copy.deepcopy(job),'log':job.get('log',[])[-30:]} if job else None,'lastOperation':{**copy.deepcopy(latest),'log':latest.get('log',[])[-30:]} if latest else None,
                    'detail':'本机 app-server' if engine=='codex' else '已启用时按任务启动进程'})
            return {'data':result}

    def require_enabled(self, engine, activate=True):
        self._validate(engine)
        with self.lock:
            if engine in self.busy:raise RuntimeError('该助手正在执行管理操作，请稍后重试')
            if not self.enabled[engine]:raise RuntimeError('该助手已关闭，请在“Agent 管理”中启动')
            # Browsing another agent (model list, thread list, thread read) must not
            # tear down the running Codex process.  Only a real turn switches the
            # single active runtime; Codex itself still has to be started when read.
            if self.single_agent and (engine == 'codex' or activate):self._activate(engine)
            if engine=='codex' and (not self.app.process or self.app.process.poll() is not None):raise RuntimeError('Codex 已退出，请在“Agent 管理”中重新启动')

    def _activate(self, engine):
        """Single-runtime mobile mode: release idle agents, never interrupt work."""
        for other in self.enabled:
            if other == engine:continue
            active = self.app.active_turns if other == 'codex' else self.bridges[other].runs
            if active or self.inflight[other] or other in self.busy:
                raise RuntimeError('为节省手机资源，请先等待或停止另一助手的任务，再切换助手')
        if engine != 'codex' and self.app.process and self.app.process.poll() is None:
            self.app.stop()
        if engine == 'codex' and (not self.app.process or self.app.process.poll() is not None):
            self.app.start()
            self.errors.pop('codex',None)

    @contextmanager
    def request(self, engine, activate=True):
        with self.lock:
            self.require_enabled(engine, activate);self.inflight[engine]+=1
        try:yield
        finally:
            with self.lock:self.inflight[engine]-=1

    def action(self, engine, action, confirmed=False):
        self._validate(engine)
        if action not in ('start','stop','update'):raise ValueError('不支持的助手操作')
        with self.lock:
            if self.closing:raise RuntimeError('工作台正在关闭')
            if engine in self.busy:raise ValueError('该助手已有操作在进行中')
            if self.single_agent and action=='update':
                for other in self.enabled:
                    if other == engine:continue
                    active = self.app.active_turns if other == 'codex' else self.bridges[other].runs
                    if active or self.inflight[other]:raise ValueError('请先等待或停止另一助手的任务，再执行管理操作')
            if self.inflight[engine]:raise ValueError('该助手有请求正在提交，请稍后重试')
            active=bool(self.app.active_turns) if engine=='codex' else bool(self.bridges[engine].runs)
            if action=='update' and active:raise ValueError('请先停止该助手正在运行的任务，再更新')
            if action=='stop' and active and not confirmed:raise ValueError('该助手还有运行中的任务，需要确认后关闭')
            if action=='update':
                if not confirmed:raise ValueError('请确认更新操作')
                if any(self.jobs.get(job_id,{}).get('action')=='update' for job_id in self.busy.values()):raise ValueError('另一助手正在更新，请等待完成')
                source,plan=self._update_plan(engine)
                if plan is None:raise ValueError(source+'，不能自动更新')
            else:plan=None
            job_id=uuid.uuid4().hex
            job={'id':job_id,'engine':engine,'action':action,'status':'pending','createdAt':time.time(),'log':[]}
            self.jobs[job_id]=job;self.busy[engine]=job_id
            # Persist the selected service state before stopping. A page reload or
            # server restart must never silently re-enable a user-disabled engine.
            if action=='stop':self.enabled[engine]=False
            self.jobs=dict(list(self.jobs.items())[-30:]);self._save()
            # Allow package managers to finish safely during graceful shutdown.
            threading.Thread(target=self._run,args=(job,plan),daemon=action!='update').start()
            return {'job':copy.deepcopy(job)}

    def _log(self, job, text):
        with self.lock:
            job['log']=(job['log']+[text[-2000:]])[-100:]
            self._save()

    def _run_command(self, job, command):
        env=dict(os.environ);env['DEBIAN_FRONTEND']='noninteractive';env['CI']='1';env.pop('CLAUDECODE',None)
        self._log(job,'执行：'+' '.join(command))
        process=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',env=env,start_new_session=True)
        timed_out=threading.Event()
        def timeout():
            timed_out.set()
            # SIGTERM is enough for apt to release its package lock; do not
            # forcibly kill dpkg in the middle of unpacking a package.
            try:process.terminate()
            except ProcessLookupError:pass
        timer=threading.Timer(900,timeout);timer.daemon=True;timer.start()
        try:
            for line in process.stdout:self._log(job,line.rstrip())
            code=process.wait()
            if timed_out.is_set():raise RuntimeError('更新命令超时，请检查网络和安装状态')
            if code:raise RuntimeError('更新命令退出码：'+str(code))
        finally:
            timer.cancel();process.stdout.close()

    def _run(self, job, plan):
        engine,action=job['engine'],job['action'];was_enabled=self.enabled[engine]
        try:
            with self.lock:job['status']='running';self._save()
            if action=='start':
                self._log(job,'正在启动 '+engine)
                if self.single_agent:
                    # Mobile start enables a verified CLI. It does not eagerly
                    # allocate three runtimes or interrupt another Agent's work.
                    version=self._version(engine,True)
                    if not version.get('available'):raise RuntimeError(version.get('error') or 'Agent 尚未安装')
                    self._log(job,'已启动，空闲就绪；发送任务时按需运行')
                elif engine=='codex':self.app.start()
                else:
                    self.bridges[engine].status_cache=None;self.bridges[engine].models=None;self.bridges[engine].model_list()
                with self.lock:
                    self.enabled[engine]=True;self.errors.pop(engine,None);self.versions.pop(engine,None)
            elif action=='stop':
                if engine=='codex':self.app.stop()
                else:self.bridges[engine].close()
                self._log(job,'已关闭，项目文件与会话历史已保留')
            else:
                if engine=='codex':self.app.stop()
                else:self.bridges[engine].models=None;self.bridges[engine].status_cache=None
                for command in plan:
                    if self.closing:raise RuntimeError('工作台正在关闭，更新未继续')
                    self._run_command(job,command)
                self.versions.pop(engine,None)
                version=self._version(engine,True)
                if not version['installed']:raise RuntimeError(version.get('error','更新后无法读取版本'))
                if was_enabled and not self.closing:
                    if engine=='codex':self.app.start()
                    else:self.bridges[engine].model_list()
                self._log(job,'更新检查完成：'+version['version'])
                self.errors.pop(engine,None)
            with self.lock:job['status']='completed'
        except Exception as error:
            with self.lock:
                job.update(status='failed',error=str(error));self.errors[engine]=str(error)
                if action=='start':self.enabled[engine]=False
            if action=='start' and engine=='codex':self.app.stop()
            self._log(job,'操作失败：'+str(error))
            # A failed download should leave the old installed Codex usable.
            if action=='update' and was_enabled and engine=='codex' and not self.closing:
                try:
                    self.app.start();self.errors.pop(engine,None)
                    self._log(job,'已恢复当前已安装版本的 Codex')
                except Exception as restart_error:self._log(job,'恢复启动失败：'+str(restart_error))
            elif action=='update' and engine!='codex':
                # An updater failure does not disable an otherwise healthy CLI.
                self.versions.pop(engine,None)
                if self._version(engine,True)['available']:self.errors.pop(engine,None)
        finally:
            with self.lock:
                job['finishedAt']=time.time();self.busy.pop(engine,None);self._save()
            self.app._emit({'type':'notification','method':'engines/changed','params':{'engine':engine,'action':action,'status':job['status']}})

    def read_job(self, job_id):
        with self.lock:
            if job_id not in self.jobs:raise ValueError('管理操作不存在')
            return {'job':copy.deepcopy(self.jobs[job_id])}
