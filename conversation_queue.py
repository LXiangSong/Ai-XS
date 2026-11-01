"""Durable follow-up messages, distinct from in-flight Agent turns and resource leases."""
import copy
import threading
import time
from collaboration_store import ACTIVE, ident

PENDING={'queued','dispatching','including','failed','needs_reconciliation'}


def inputs(value):
    if not isinstance(value,list) or not 1<=len(value)<=32:raise ValueError('消息内容无效')
    result=copy.deepcopy(value)
    for part in result:
        if not isinstance(part,dict) or part.get('type') not in ('text','localImage'):raise ValueError('消息类型无效')
        field='text' if part['type']=='text' else 'path';text=part.get(field)
        if not isinstance(text,str) or not text.strip() or len(text)>28000:raise ValueError('消息内容为空或过长')
    if sum(len(p.get('text','')) for p in result)>28000:raise ValueError('消息过长')
    return result


class ConversationQueue:
    def __init__(self, service):
        self.s=service;self.live=set();self.threads=set()
        for key,row in list(service.chats.index.items()):
            if row.get('deleted'):continue
            with service.chats.lock:
                doc=service.chats._load(key);entries=doc.get('messageQueue',[])
                if not any(e['status'] in PENDING for e in entries):continue
                for e in entries:
                    if e['status'] in ('dispatching','including'):
                        e.update(status='needs_reconciliation',error='服务重启，发送结果未知；请核对会话，不会自动重发',revision=e['revision']+1)
                control=doc.get('executionControl') or {}
                resume=next((e for e in entries if e['id']==control.get('resumeMessageId')),None)
                if resume and resume['status'] in ('failed','needs_reconciliation'):control.update(status=resume['status'],error=resume.get('error',''))
                # A clean queued hand-off may continue after restart. Explicit user pause
                # and unknown in-flight results still require a manual decision.
                unknown=any(e['status'] in ('needs_reconciliation','dispatching','including') for e in entries)
                # Older versions saved only the boolean. Missing metadata must
                # not turn a user's paused queue into runnable work on upgrade.
                explicit=bool(doc.get('messageQueuePaused')) and doc.get('messageQueuePauseReason') in (None,'','user')
                doc['messageQueuePaused']=bool(unknown or explicit)
                if explicit:doc['messageQueuePauseReason']='user'
                elif unknown:doc['messageQueuePauseReason']='recovery'
                else:doc.pop('messageQueuePauseReason',None)
                service.chats._save(doc)

    def changed(self, key):
        self.s.emit({'type':'notification','method':'native/queueChanged','params':{'threadId':key}})

    def busy(self, doc):
        s=self.s;key=doc['id']
        return key in s.chats.busy or s.chats._running(doc) or key in s.normal_active or any(
            any(t['status'] in ACTIVE|{'needs_reconciliation'} for t in w['tasks']) or
            w['status'] in ('running','paused') and any(t['status'] in ('queued','blocked') for t in w['tasks'])
            for w in s.store.listing(key))

    def pending(self, doc):return any(e['status'] in PENDING for e in doc.get('messageQueue',[])) or doc['id'] in self.live or (doc.get('executionControl') or {}).get('status') in ('stopping','interrupted','pausing','paused','resuming','failed','needs_reconciliation')

    def submit(self, doc, params, mode, continuation=False):
        parts=inputs(params.get('input'));request=params.get('clientRequestId') or params.get('clientUserMessageId') or ident()
        if not isinstance(request,str) or not 1<=len(request)<=200:raise ValueError('缺少发送请求编号')
        if mode not in ('independent','collaboration'):raise ValueError('执行模式无效')
        if doc.get('archived'):raise ValueError('请先恢复已归档的会话')
        # A retry can reach this endpoint after an immediate send started work.
        replay=getattr(self.s,'normal_replay',lambda *args:None)(doc,params)
        if replay is not None:return replay
        with self.s.chats.lock:
            entries=doc.setdefault('messageQueue',[])
            previous=next((e for e in entries if e['clientRequestId']==request),None)
            if previous:return self.response(doc,previous)
            if not continuation and sum(e['status'] in PENDING for e in entries)>=100:raise ValueError('队列已达 100 条，请先处理已有消息')
            config={k:copy.deepcopy(doc[k]) for k in ('collaborationParticipants','collaborationSummarize','collaborationTemplate','collaborationBudget') if k in doc}
            entry={'id':ident(),'clientRequestId':request,'input':parts,'mode':mode,'status':'queued','revision':1,
                   'createdAt':time.time(),'updatedAt':time.time(),'params':{k:copy.deepcopy(params[k]) for k in ('engine','model','effort','template','budget','continuationOf') if k in params},
                   'collaborationConfig':config,'error':''}
            if mode=='independent':entry['params'].setdefault('engine',doc['engine'])
            if mode=='independent' and entry['params']['engine'] not in ('codex','claude','deepseek'):raise ValueError('未知 Agent')
            entries.append(entry)
            # Retain bounded idempotency receipts; never discard pending user input.
            terminal=[e['id'] for e in entries if e['status'] not in PENDING][:-200]
            doc['messageQueue']=[e for e in entries if e['id'] not in terminal]
            self.s.chats._save(doc)
        if not continuation and getattr(self.s,'execution',None):self.s.execution.accept_new_input(doc)
        self.changed(doc['id']);return self.response(doc,entry)

    def response(self, doc, entry=None):
        result={'thread':self.s.chats.view(doc),'entries':copy.deepcopy(doc.get('messageQueue',[])),
                'paused':bool(doc.get('messageQueuePaused'))}
        if entry:result.update(entry=copy.deepcopy(entry),queued=entry['status']=='queued',message='已排队，当前任务结束后自动发送；可编辑、删除或引入当前任务')
        return result

    def mutate(self, key, entryid, fn):
        with self.s.chats.lock:
            doc=self.s.chats.adopt(key);entry=next(e for e in doc['messageQueue'] if e['id']==entryid)
            fn(entry);entry['revision']+=1;entry['updatedAt']=time.time()
            if entry['status'] in ('sent','included','deleted'):
                entry.pop('collaborationConfig',None);entry.pop('params',None)
                if entry['status']=='deleted':entry.pop('input',None)
            self.s.chats._save(doc)
        self.changed(key)

    def handle(self, action, params):
        s=self.s
        with s.lock:
            doc=s.chats.adopt(params['threadId'])
            if action=='submit':return self.submit(doc,params,params.get('mode',doc.get('executionMode','independent')))
            if action=='list':return self.response(doc)
            if action in ('pause','resume'):
                with s.chats.lock:
                    doc['messageQueuePaused']=action=='pause'
                    if action=='pause':doc['messageQueuePauseReason']='user'
                    else:doc.pop('messageQueuePauseReason',None)
                    s.chats._save(doc)
                self.changed(doc['id']);return self.response(doc)
            entry=next((e for e in doc.get('messageQueue',[]) if e['id']==params.get('messageId')),None)
            if not entry:raise ValueError('排队消息不存在')
            if entry.get('executionResume') and action not in ('retry',):raise ValueError('这是原任务的继续请求，请使用执行按钮继续')
            if action=='include' and entry['status'] in ('including','included'):return self.response(doc)
            if params.get('revision') is not None and params['revision']!=entry['revision']:raise ValueError('排队消息已变化，请刷新')
            if entry['status'] not in ('queued','failed','needs_reconciliation'):raise ValueError('消息正在发送或已处理，不能修改')
            if action=='edit':
                if entry['status']=='needs_reconciliation':raise ValueError('发送结果未知，请核对后再重试')
                parts=inputs(params.get('input'))
                self.mutate(doc['id'],entry['id'],lambda e:e.update(input=parts,status='queued',error=''))
            elif action=='delete':self.mutate(doc['id'],entry['id'],lambda e:e.update(status='deleted'))
            elif action=='retry':
                if entry['status']=='needs_reconciliation' and params.get('confirmed') is not True:raise ValueError('请先核对未知发送结果，确认不会重复执行')
                if entry['status']=='queued':raise ValueError('消息已在队列中')
                self.mutate(doc['id'],entry['id'],lambda e:e.update(status='queued',error=''))
                if entry.get('executionResume'):
                    doc['executionControl'].update(status='resuming',error='');s.execution.changed(doc)
            elif action=='include':
                if entry['status']!='queued':raise ValueError('仅可引入尚未发送的排队消息')
                if doc['id'] in self.live:raise ValueError('队列发送正在交接，请稍后引入')
                self.include(doc,entry,params)
            else:raise ValueError('未知队列操作')
            return self.response(doc)

    def tick(self):
        s=self.s
        if s.closed.is_set() or s.scheduler.closed:return
        with s.chats.lock:rows=list(s.chats.index.items())
        for key,row in rows:
            if row.get('deleted') or key in self.live:continue
            with s.chats.lock:doc=s.chats._load(key)
            execution=getattr(s,'execution',None)
            if execution:execution.refresh(doc)
            control=doc.get('executionControl') or {}
            resuming=control.get('status')=='resuming'
            head=next((e for e in doc.get('messageQueue',[]) if e['status'] in PENDING),None)
            if (execution and head and head['status']=='queued' and not head.get('executionResume')
                    and control.get('status') in ('interrupted','paused')
                    and not doc.get('messageQueuePaused')
                    and not self.busy(doc)):
                # The interrupted task no longer owns the next turn. Ordinary
                # follow-ups resume automatically instead of waiting for a
                # separate "continue" click; an explicit pause still wins.
                try:execution.accept_new_input(doc)
                except Exception:pass
                control=doc.get('executionControl') or {}
            if control.get('status') in ('stopping','interrupted','pausing','paused','failed','needs_reconciliation'):continue
            if doc.get('messageQueuePaused') and not resuming or self.busy(doc):continue
            head=next((e for e in doc.get('messageQueue',[]) if e['status'] in PENDING),None)
            if not head or head['status']!='queued':continue
            if resuming and head['id']!=control.get('resumeMessageId'):continue
            if head['mode']=='independent':
                reason='等待独立任务执行名额' if s.normal_active else s.scheduler.reason('followup:'+head['id'],doc['cwd'],head['params']['engine'],normal=True,device=True,browser=True)
                if reason:
                    if head.get('waitReason')!=reason:self.mutate(key,head['id'],lambda e:e.update(waitReason=reason))
                    continue
            self.mutate(key,head['id'],lambda e:e.update(status='dispatching'));self.live.add(key)
            def dispatch(key=key,entry=copy.deepcopy(head)):
                try:
                    with s.lock:
                        if s.closed.is_set():raise ValueError('服务正在关闭，未发送')
                        doc=s.chats.adopt(key)
                        params=dict(entry['params'],threadId=key,input=entry['input'],clientRequestId=entry['id'],clientUserMessageId=entry['id'])
                        if entry['mode']=='collaboration':
                            configured=copy.deepcopy(doc);configured.update(entry['collaborationConfig']);configured['executionMode']='collaboration'
                            result=s.sessions.submit(configured,params,from_queue=True)
                        else:result=s.normal_submit('turn/start',params,from_queue=True)
                    receipt={k:copy.deepcopy(result[k]) for k in ('turn','workflow') if k in result}
                    receipt={k:v.get('id') for k,v in receipt.items()}
                    self.mutate(key,entry['id'],lambda e:e.update(status='sent',receipt=receipt))
                except Exception as error:
                    self.mutate(key,entry['id'],lambda e:e.update(status='needs_reconciliation' if isinstance(error,TimeoutError) else 'failed',error=str(error)))
                finally:
                    with s.lock:
                        self.live.discard(key)
                        if execution:
                            with s.chats.lock:
                                current=s.chats.adopt(key);saved=next(e for e in current['messageQueue'] if e['id']==entry['id'])
                                execution.dispatched(current,saved)
                    self.changed(key)
            self.start_thread(dispatch,'conversation-followup')

    def start_thread(self, fn, name):
        def run():
            try:fn()
            finally:
                with self.s.lock:self.threads.discard(threading.current_thread())
        thread=threading.Thread(target=run,name=name,daemon=True);self.threads.add(thread);thread.start()

    def include(self, doc, entry, params):
        s=self.s;key=doc['id'];targets=[];workflow=None
        active=[t for t in doc['turns'] if t.get('status')=='inProgress' and not t.get('queued')]
        if active:
            target=active[-1];binding=doc.get('binding') or {}
            if target.get('engine')!=binding.get('engine'):raise ValueError('当前执行绑定已变化，请刷新')
            targets=[{'mode':'independent','turnId':target['id'],'engine':binding['engine']}]
        else:
            workflows=[w for w in s.store.listing(key) if any(t['status'] in ACTIVE for t in w['tasks'])]
            if not workflows:raise ValueError('当前没有可引入的执行；消息继续排队')
            workflow=workflows[0]
            selected=params.get('taskIds')
            if selected is not None and (not isinstance(selected,list) or not selected or any(i not in [t['id'] for t in workflow['tasks']] for i in selected)):
                raise ValueError('目标子任务无效')
            for t in workflow['tasks']:
                if selected is not None and t['id'] not in selected:continue
                if t['status'] in ('stopping','needs_reconciliation'):raise ValueError('目标正在停止或待核对，暂不能引入')
                if t['status'] in ACTIVE:
                    a=t['attempts'][-1];targets.append({'mode':'collaboration','taskId':t['id'],'attemptId':a['id'],'engine':a['config']['engine']})
            if not targets:raise ValueError('没有可引入的运行中成员')
        self.mutate(key,entry['id'],lambda e:e.update(status='including',targets=targets));self.live.add(key)
        try:
            if workflow:
                if len(workflow.get('messages',[]))>=200:raise ValueError('本轮补充要求已达上限，请新建一轮')
                text='\n'.join(p.get('text','') for p in entry['input'] if p['type']=='text') or '请结合补充附件继续'
                def requirements(w):
                    w.setdefault('messages',[]).append({'id':entry['id'],'clientRequestId':'queue:'+entry['id'],'text':text,'input':entry['input'],
                        'at':time.time(),'participantId':'user','taskId':'','taskIds':params.get('taskIds'),'delivery':'live_guidance'})
                s.store.update(workflow['id'],requirements);s.changed(workflow['id'])
        except Exception as error:
            self.live.discard(key);self.mutate(key,entry['id'],lambda e:e.update(status='failed',error=str(error)));raise
        def deliver():
            receipts=[]
            try:
                for target in targets:
                    receipt=dict(target)
                    try:
                        args={'input':entry['input'],'clientUserMessageId':entry['id']}
                        if target['mode']=='independent':
                            s.chats.handle('turn/steer',dict(args,threadId=key,expectedTurnId=target['turnId']))
                        else:s.workers.steer(target['attemptId'],args)
                        receipt.update(status='accepted',delivery='native_steer' if target['engine']=='codex' else 'interrupt_continue')
                    except Exception as error:receipt.update(status='unknown' if isinstance(error,TimeoutError) else 'failed',error=str(error))
                    receipts.append(receipt)
                    self.mutate(key,entry['id'],lambda e:e.update(receipts=copy.deepcopy(receipts)))
                ok=all(r['status']=='accepted' for r in receipts)
                unknown=any(r['status']=='unknown' for r in receipts)
                self.mutate(key,entry['id'],lambda e:e.update(status='included' if ok else 'needs_reconciliation' if unknown else 'failed',error='' if ok else '部分引入未确认，请核对各成员回执；不会自动重发'))
            finally:
                with s.lock:self.live.discard(key)
                self.changed(key)
        self.start_thread(deliver,'conversation-guidance')
