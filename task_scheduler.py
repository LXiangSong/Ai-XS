"""One admission controller for ordinary conversations and collaboration attempts."""
import threading
import time


class TaskScheduler:
    def __init__(self, settings, external, enabled):
        self.settings,self.external,self.enabled=settings,external,enabled
        self.lock=threading.RLock();self.leases={};self.sample=None;self.closed=False;self.backoffs={}

    def backoff(self, group, until):
        with self.lock:self.backoffs[group]=max(self.backoffs.get(group,0),until)

    def resources(self, value):
        with self.lock:
            self.sample={**value,'receivedAt':time.monotonic()}

    def reason(self, key, project, engine, heavy=False, device=False, browser=False, normal=False, group=""):
        if not self.enabled(engine):return 'Agent 已停止，请启动或改派'
        if self.closed:return '服务正在关闭'
        limit=self.settings()['parallel'];external=self.external()
        total=len(self.leases)+external['count']
        if total>=limit:return '等待全局执行名额'
        if external['count'] and (device or browser):return '等待独立任务释放手机 / 浏览器资源'
        sample=self.sample
        if sample is None or time.monotonic()-sample['receivedAt']>20:return '等待手机资源信息'
        if sample.get('lowMemory') or sample.get('available',0)<1024*1024*1024:return '可用内存不足，暂停派发'
        if sample.get('thermal',0)>=4:return '设备过热，暂停派发'
        if group and self.backoffs.get(group,0)>time.time():return 'API 来源退避中，等待 '+str(max(1,int(self.backoffs[group]-time.time())))+' 秒'
        if group and sum(lease.get('group')==group for lease in self.leases.values())>=self.settings().get('sourceParallel',2):return '等待 API 来源并发名额'
        for owner,lease in self.leases.items():
            if owner==key:continue
            if heavy and lease['heavy']:return '等待重任务名额'
            if device and lease['device']:return '等待手机控制资源'
            if browser and lease['browser']:return '等待浏览器资源'
            if project==lease['project'] and (normal or lease['normal']):return '同项目独立会话与协作写入互斥'
        if project in external.get('projects',[]):return '等待同项目独立任务结束'
        return ''

    def reserve(self, key, project, engine, **flags):
        with self.lock:
            reason=self.reason(key,project,engine,**flags)
            if reason:return reason
            self.leases[key]={'project':project,'engine':engine,'heavy':flags.get('heavy',False),
                'device':flags.get('device',False),'browser':flags.get('browser',False),'normal':flags.get('normal',False),'group':flags.get('group','')}
            return ''

    def release(self, key):
        with self.lock:self.leases.pop(key,None)

    def status(self):
        with self.lock:return {'running':len(self.leases)+self.external()['count'],**self.settings(),
            'heavyRunning':sum(v['heavy'] for v in self.leases.values())}
