"""Interrupt current execution and explicitly continue it before queued follow-ups."""
import copy
import time
from collaboration_store import ACTIVE, ident
import collaboration_policy as policy
CONTINUE='继续刚才中断的任务，沿用原目标、要求与已有进度。先检查已完成的操作和文件，只完成剩余工作，不要重复执行已发生的外部操作。'


class ConversationExecution:
    def __init__(self, service):self.s=service

    def changed(self, doc):
        with self.s.chats.lock:self.s.chats._save(doc)
        self.s.queue.changed(doc['id'])

    def refresh(self, doc):
        control=doc.get('executionControl') or {}
        legacy={'pausing':'stopping','paused':'interrupted'}
        if control.get('status') in legacy:
            control['status']=legacy[control['status']];self.changed(doc)
        if control.get('status')!='stopping' or doc['id'] in self.s.queue.live:return
        if control['mode']=='independent':
            if self.s.queue.busy(doc):return
            turn=next((t for t in doc['turns'] if t['id']==control['turnId']),None)
            if not turn or turn.get('status')!='interrupted':
                control.update(status='completed',finishedStatus=(turn or {}).get('status','missing'),error='原任务已结束，无需继续')
                self.changed(doc);return
        else:
            workflow=self.s.store.read(control['workflowId'])
            if any(t['status'] in ACTIVE for t in workflow['tasks']):return
            if any(t['status']=='needs_reconciliation' for t in workflow['tasks']):
                control.update(status='needs_reconciliation',error='成员状态未知，请先在本轮协作中核对')
                self.changed(doc);return
        control.update(status='interrupted',stoppedAt=time.time());self.changed(doc)
        if control.get('newInputPending'):self.accept_new_input(doc)

    def handle(self, action, params):
        s=self.s
        with s.lock:
            doc=s.chats.adopt(params['threadId']);self.refresh(doc)
            if action in ('interrupt','pause'):self.interrupt(doc,params)
            elif action=='resume':self.resume(doc)
            elif action!='read':raise ValueError('未知执行控制')
            return s.queue.response(doc)

    def interrupt(self, doc, params):
        s=self.s;key=doc['id'];previous=doc.get('executionControl') or {}
        if previous.get('status') in ('stopping','interrupted'):return
        if key in s.queue.live or previous.get('status')=='resuming':raise ValueError('任务正在交接，请稍后停止')
        turns=[t for t in doc['turns'] if t.get('status')=='inProgress']
        control={'id':ident(),'status':'stopping','requestedAt':time.time(),'error':''}
        if turns:
            turn=turns[-1]
            if params.get('turnId') and params['turnId']!=turn['id']:raise ValueError('当前任务已变化，请刷新')
            config=copy.deepcopy(turn.get('executionRequest') or {})
            if turn.get('queued'):
                queued=s.normals.get(turn['id'],{}).get('params',{})
                config.update({k:copy.deepcopy(queued[k]) for k in ('engine','model','effort') if k in queued})
            config.setdefault('engine',turn.get('engine',doc['engine']))
            config.setdefault('model',turn.get('model') or doc['model'])
            binding=doc.get('binding') or {}
            control.update(mode='independent',turnId=turn['id'],params=config,binding={'threadId':binding.get('threadId'),'engine':binding.get('engine')},
                           queued=bool(turn.get('queued')))
            doc['executionControl']=control;self.changed(doc);s.queue.live.add(key)
            def interrupt():
                try:
                    args={'threadId':key,'turnId':turn['id']}
                    if not s.normal_cancel(args):s.chats.handle('turn/interrupt',args)
                except Exception as error:
                    with s.lock:
                        control.update(status='needs_reconciliation' if isinstance(error,TimeoutError) else 'failed',error=str(error));self.changed(doc)
                finally:
                    with s.lock:s.queue.live.discard(key)
                    s.queue.changed(key)
            s.queue.start_thread(interrupt,'conversation-interrupt')
            return
        workflows=[w for w in s.store.listing(key) if w['status'] in ('running','paused') and
                   any(t['status'] in ACTIVE|{'queued','blocked'} for t in w['tasks'])]
        if not workflows:raise ValueError('当前没有正在执行的任务')
        workflow=workflows[0]
        if params.get('workflowId') and params['workflowId']!=workflow['id']:raise ValueError('当前协作已变化，请刷新')
        control.update(mode='collaboration',workflowId=workflow['id'],
                       taskIds=[t['id'] for t in workflow['tasks'] if t['status'] in ACTIVE])
        def paused(w):
            w['status']='paused';w['pauseReasons']=list(set(w.get('pauseReasons',[])+['user']))
            w.setdefault('executionPauseStartedAt',time.time())
        workflow,_=s.store.update(workflow['id'],paused)
        doc['executionControl']=control;self.changed(doc)
        s._stop_all(workflow);s.changed(workflow['id'])

    def resume(self, doc):
        s=self.s;control=doc.get('executionControl') or {};state=control.get('status')
        if state in ('resuming','running'):return
        if state not in ('interrupted','failed','needs_reconciliation'):raise ValueError('请等待当前执行停止后再继续')
        if control.get('resumeMessageId'):
            entry=next(e for e in doc['messageQueue'] if e['id']==control['resumeMessageId'])
            if entry['status']=='needs_reconciliation':raise ValueError('继续请求结果未知，请先核对队列详情，再确认重试')
            if entry['status']=='failed':
                s.queue.handle('retry',{'threadId':doc['id'],'messageId':entry['id']});return
        if control['mode']=='collaboration':
            workflow=s.store.read(control['workflowId'])
            if any(t['status'] in ACTIVE|{'needs_reconciliation'} for t in workflow['tasks']):raise ValueError('请等待成员退出并核对未知执行后再继续')
            if workflow['status']!='paused':raise ValueError('原协作状态已变化，请在本轮协作中检查')
            selected=[t for t in workflow['tasks'] if t['id'] in control['taskIds'] and t['status']=='interrupted']
            if any(t['status'] in ('failed','cancelled') for t in workflow['tasks'] if t['id'] in control['taskIds']):
                raise ValueError('部分成员执行失败或已取消，请在本轮协作中检查后重试')
            metrics=policy.metrics(workflow)
            remaining=selected or any(t['status'] in ('queued','blocked') for t in workflow['tasks'])
            if remaining and (not metrics['canDispatch'] or metrics['attempts']+len(selected)>metrics['limits']['maxAttempts']):
                raise ValueError('已达到本轮预算限制，请先调整预算')
            if any(len(t['attempts'])>=t.get('maxAttempts',3) for t in selected):raise ValueError('已达到任务执行次数限制，请先调整限制')
            def resume_workflow(w):
                for t in w['tasks']:
                    if any(t['id']==item['id'] for item in selected):
                        t.update(status='queued',error='',waitReason='',approvals=[]);t.pop('verification',None)
                w.update(status='running',pauseReasons=[])
                w['executionPausedSeconds']=w.get('executionPausedSeconds',0)+max(0,time.time()-w.pop('executionPauseStartedAt',time.time()))
            s.store.update(workflow['id'],resume_workflow)
            control.update(status='running',resumedAt=time.time(),error='');self.changed(doc);s.changed(workflow['id'])
            return
        if s.queue.busy(doc):raise ValueError('当前执行尚未退出，请稍后继续')
        turn=next((t for t in doc['turns'] if t['id']==control['turnId']),None)
        if not turn or turn.get('status')!='interrupted':raise ValueError('原任务没有中断或已完成，请刷新会话')
        if control.get('queued'):
            parts=copy.deepcopy(next((i.get('content',[]) for i in turn['items'] if i.get('type')=='userMessage'),[]))
        else:
            binding=doc.get('binding') or {};original=control.get('binding') or {}
            if binding.get('threadId')!=original.get('threadId') or doc['engine']!=control['params']['engine']:
                raise ValueError('原任务的 Agent 会话已变化，不能自动继续')
            parts=[{'type':'text','text':CONTINUE}]
        params=dict(control['params'],input=parts,clientRequestId='continue:'+control['id'],continuationOf=control['turnId'])
        entry=s.queue.submit(doc,params,'independent',continuation=True)['entry']
        with s.chats.lock:
            items=doc['messageQueue'];items.insert(0,items.pop(next(i for i,e in enumerate(items) if e['id']==entry['id'])))
            items[0]['executionResume']=True
            control.update(status='resuming',resumeMessageId=entry['id'],error='');self.changed(doc)

    def dispatched(self, doc, entry):
        control=doc.get('executionControl') or {}
        if control.get('resumeMessageId')!=entry['id']:return
        control.update(status='running' if entry['status']=='sent' else entry['status'],error=entry.get('error',''))
        if entry.get('receipt'):control['receipt']=copy.deepcopy(entry['receipt'])
        self.changed(doc)

    def accept_new_input(self, doc):
        """An explicit new message replaces the empty-input continuation shortcut."""
        s=self.s;control=doc.get('executionControl') or {}
        if control.get('status') in ('stopping','pausing'):
            control['newInputPending']=True;self.changed(doc);return
        if control.get('status') not in ('interrupted','paused'):return
        if control.get('mode')=='collaboration':
            workflow=s.store.read(control['workflowId'])
            if any(t['status'] in ACTIVE|{'needs_reconciliation'} for t in workflow['tasks']):
                raise ValueError('旧执行尚未退出或待核对，请先处理')
            def superseded(w):
                w['status']='cancelled'
                for task in w['tasks']:
                    if task['status'] in ('queued','blocked'):task['status']='cancelled'
            s.store.update(workflow['id'],superseded);s.changed(workflow['id'])
        control.update(status='superseded',supersededAt=time.time());self.changed(doc)
