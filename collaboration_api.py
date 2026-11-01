"""Backend operations consumed by both the current and next collaboration UI."""
import copy
import hashlib
import json
import os
from pathlib import Path
import signal
import time
from collaboration_store import ACTIVE, ident
from worker_pool import process_identity
import collaboration_policy as policy
import collaboration_workspace as workspace
from provider_manager import private_write

UNHANDLED=object()


def handle(service, action, params):
    s=service
    if action=='capabilities':
        return {'version':2,'features':['integration-preview','batch-integration','staged-plans','verification',
            'rework','recovery-inspection','recovery-stop','history-pagination','requirements','handoff','budget','source-backoff','workspace-cleanup'],
            'templates':['auto','parallel','development','research'],'messageDelivery':['next_attempt','restart'],
            'supportsLiveInjection':True,'guidanceDelivery':{'codex':'native_steer','claude':'interrupt_continue','deepseek':'interrupt_continue'},
            'messageQueue':'native/chat/queue/','executionControl':'native/chat/execution/','supportsExecutionInterrupt':True,'supportsExecutionPause':False,'tokenBudget':'reported_usage_only'}
    supported={'integration/preview','integration/apply','recovery/read','recovery/stop','message','task/handoff','task/config','policy','cleanup/preview','cleanup'}
    if action not in supported:return UNHANDLED
    key=params['workflowId'];doc=s.store.read(key)
    if action=='task/config':
        if doc['status'] not in ('draft','paused','partial_failure') or any(t['status'] in ACTIVE|{'needs_reconciliation'} for t in doc['tasks']):raise ValueError('请先暂停并等待所有执行退出，再调整任务')
        task=next(t for t in doc['tasks'] if t['id']==params['taskId'])
        if task['status']=='succeeded':raise ValueError('已完成任务请通过重试创建新尝试，不改写原验收配置')
        fields={'kind','validation','maxAttempts','dependencies','reviewTargets','goal','acceptance','title','participantId','heavy','device','browser'}
        change=params.get('config',{})
        if not isinstance(change,dict) or any(k not in fields for k in change):raise ValueError('任务配置字段无效')
        candidate=copy.deepcopy(doc);target=next(t for t in candidate['tasks'] if t['id']==task['id']);target.update(change)
        _,validated,_=s._plan(candidate)
        value=next(t for t in validated if t['id']==task['id'])
        if value['maxAttempts']<len(task.get('attempts',[])):raise ValueError('次数限制不能小于已有执行次数')
        def save(d):
            target=next(t for t in d['tasks'] if t['id']==task['id'])
            for field in fields:
                if field in value:target[field]=copy.deepcopy(value[field])
        doc,_=s.store.update(key,save,params.get('revision'));s.changed(key);return {'workflow':s.view(doc)}
    if action=='policy':
        limits=policy.budget(params.get('budget',doc.get('budget')))
        rework=params.get('rework',doc.get('rework',{}))
        if not isinstance(rework,dict) or type(rework.get('maxRounds',2)) is not int or not 0<=rework.get('maxRounds',2)<=5 or type(rework.get('auto',False)) is not bool:raise ValueError('返工配置无效')
        doc,_=s.store.update(key,lambda d:d.update(budget=limits,rework={'auto':rework.get('auto',False),'maxRounds':rework.get('maxRounds',2)}),params.get('revision'))
        s.changed(key);return {'workflow':s.view(doc)}
    if action in ('integration/preview','integration/apply'):
        ids=params.get('taskIds')
        if not isinstance(ids,list) or not ids or len(set(ids))!=len(ids):raise ValueError('请选择要合入的任务编号')
        selected=[t for t in doc['tasks'] if t['id'] in ids]
        if len(selected)!=len(ids) or any(t['status']!='succeeded' or t.get('stale') for t in selected):raise ValueError('只可合入当前已完成且未过期的成果')
        if any(not t.get('artifact',{}).get('patch') for t in selected):raise ValueError('所选任务没有可合入的 Git 补丁')
        if any(t['status'] in ACTIVE|{'needs_reconciliation'} for d in s.store.listing() if d['cwd']==doc['cwd'] for t in d['tasks']):raise ValueError('项目仍有执行或待核对任务，暂不能合入')
        if s.scheduler.external()['count'] or s.normal_active:raise ValueError('请先等待独立任务结束再合入')
        ledger=s.folder/'integrations'/(key+'.json')
        try:result=workspace.integrate_many([t['artifact'] for t in selected],ledger)
        except ValueError as error:
            if action=='integration/apply':raise
            return {'status':'blocked','message':str(error),'canApply':False,'taskIds':ids}
        payload=json.dumps({'workflowId':key,'taskIds':ids,'attemptIds':[t['attempts'][-1]['id'] for t in selected],
                            'beforeTree':result['beforeTree'],'afterTree':result['afterTree']},sort_keys=True)
        token=hashlib.sha256(payload.encode()).hexdigest()
        if action=='integration/preview':return dict(result,canApply=True,previewToken=token,taskIds=ids)
        if params.get('previewToken')!=token:raise ValueError('合并预览已变化，请重新预览后合入')
        result=workspace.integrate_many([t['artifact'] for t in selected],ledger,apply=True)
        def record(d):
            for t in d['tasks']:
                if t['id'] in ids:t['artifact'].update(integration='补丁已应用到原项目（未提交），请检查并测试',integrationReceipt=copy.deepcopy(result))
        doc,_=s.store.update(key,record);s.changed(key)
        return dict(result,workflow=s.view(doc))
    if action=='message':
        request=params.get('clientRequestId');text=params.get('text');taskid=params.get('taskId','')
        if not isinstance(request,str) or not 1<=len(request)<=200 or not isinstance(text,str) or not text.strip() or len(text)>20000:raise ValueError('补充要求或请求编号无效')
        if taskid and not any(t['id']==taskid for t in doc['tasks']):raise ValueError('任务不存在')
        previous=next((m for m in doc.get('messages',[]) if m.get('clientRequestId')==request),None)
        if previous:return {'message':previous,'workflow':s.view(doc)}
        if len(doc.get('messages',[]))>=200:raise ValueError('本轮补充要求已达 200 条，请新建一轮')
        pending=[t['id'] for t in doc['tasks'] if (not taskid or t['id']==taskid) and t['status'] in ACTIVE]
        delivery=params.get('delivery','next_attempt')
        if delivery not in ('next_attempt','restart'):raise ValueError('补充要求生效方式无效')
        if delivery=='restart':
            if params.get('confirmed') is not True:raise ValueError('中断后继续会创建新尝试，请确认')
            if any(t['status']=='needs_reconciliation' for t in doc['tasks'] if not taskid or t['id']==taskid):raise ValueError('请先核对旧执行状态')
            if not policy.metrics(doc)['canDispatch'] or any(len(t.get('attempts',[]))>=t.get('maxAttempts',3) for t in doc['tasks'] if t['id'] in pending):raise ValueError('已达到执行预算，不能追加新的执行尝试')
            affected=set(pending)
            while True:
                more={t['id'] for t in doc['tasks'] if set(t.get('dependencies',[]))&affected}
                if more<=affected:break
                affected|=more
            if any(t['id'] in affected-set(pending) and t['status'] in ACTIVE|{'needs_reconciliation'} for t in doc['tasks']):raise ValueError('相关下游正在执行或待核对，请先处理相关任务')
        message={'id':ident(),'clientRequestId':request,'text':text.strip(),'at':time.time(),'participantId':'user',
                 'taskId':taskid,'delivery':delivery,'runningTaskIds':pending}
        def save(d):
            d.setdefault('messages',[]).append(message)
            if delivery=='restart':
                d['accepted']=False;d.pop('acceptanceReceipt',None)
                for t in d['tasks']:
                    if t['id'] in pending:t['pendingAssignment']=t['participantId']
                    elif t['id'] in affected:
                        t.update(status='blocked',stale=True);t.pop('verification',None)
        doc,_=s.store.update(key,save)
        if delivery=='restart':
            for t in doc['tasks']:
                if t['id'] in pending:s._stop(key,t['id'],t['attempts'][-1]['id'])
        s.changed(key)
        return {'message':message,'workflow':s.view(s.store.read(key)),'requiresRestartTaskIds':pending,
                'notice':'已请求中断，确认退出并保存成果后以新尝试继续' if delivery=='restart' else '已保存；尚未启动的任务会读取，运行中的成员需要中断后继续，不宣称已注入 CLI'}
    if action=='task/handoff':
        task=next(t for t in doc['tasks'] if t['id']==params['taskId'])
        return {'handoff':policy.handoff(doc,task)}
    if action=='recovery/stop':
        if params.get('confirmed') is not True:raise ValueError('请确认结束该旧执行实例，已发生的外部操作不会撤销')
        task=next(t for t in doc['tasks'] if t['id']==params['taskId'])
        if task['status']!='needs_reconciliation':raise ValueError('只有待核对任务可结束旧实例')
        attempt=task['attempts'][-1]
        if attempt['id'] in s.workers.live or attempt['id'] in s.transcripts:raise ValueError('执行回收仍在进行，请稍后操作')
        process=attempt.get('process',{});identities=[process,*process.get('children',[])]
        def alive(p):return p.get('pid') and p.get('identity') and process_identity(p['pid'])==p['identity']
        def send(p,sig):
            if not alive(p):return
            try:
                if os.getpgid(p['pid'])==p['pid']:os.killpg(p['pid'],sig)
                else:os.kill(p['pid'],sig)
            except ProcessLookupError:pass
        for p in identities:send(p,signal.SIGTERM)
        until=time.monotonic()+2
        while any(alive(p) for p in identities) and time.monotonic()<until:time.sleep(.05)
        for p in identities:send(p,signal.SIGKILL)
        s.store.update(key,lambda d:next(t for t in d['tasks'] if t['id']==task['id']).update(error='已请求结束旧执行；请刷新检查并核对已有成果后恢复'))
        s.changed(key)
        return {'workflow':s.view(s.store.read(key)),'notice':'仅结束身份匹配的旧执行，资源仍保留至 reconcile 核对；不会自动重放'}
    if action=='recovery/read':
        tasks=[]
        for t in doc['tasks']:
            if params.get('taskId') and t['id']!=params['taskId']:continue
            a=t.get('attempts',[])[-1] if t.get('attempts') else {};process=a.get('process',{})
            identities=[process,*process.get('children',[])];processes=[]
            for p in identities:
                if p.get('pid'):
                    processes.append({'pid':p['pid'],'identity':p.get('identity'),
                        'alive':bool(p.get('identity') and process_identity(p['pid'])==p['identity'])})
            tasks.append({'taskId':t['id'],'status':t['status'],'attemptId':a.get('id'),'processes':processes,
                          'canReconcile':t['status']=='needs_reconciliation' and a.get('id') not in s.workers.live and a.get('id') not in s.transcripts and not any(p['alive'] for p in processes),
                          'workspace':t.get('artifact',{}).get('workspace') or {'path':str(s.folder/'attempts'/a['id']/'workspace')} if a else None,
                          'error':t.get('error',''),'handoffAvailable':bool(a)})
        return {'workflowId':key,'tasks':tasks,'notice':'确认旧进程已退出，并核对文件和外部操作后调用 reconcile；不会自动重放'}
    if action in ('cleanup/preview','cleanup'):
        if any(t['status'] in ACTIVE|{'needs_reconciliation'} for t in doc['tasks']) or doc['status'] in ('running','paused','partial_failure'):
            raise ValueError('仅可清理已结束且无待核对执行的工作区')
        if not doc.get('accepted'):raise ValueError('请先确认本轮成果，再清理工作区')
        records=[]
        for a in s._attempts(doc).values():
            artifact=a.get('artifact',{});w=artifact.get('workspace')
            if not w or artifact.get('cleanedAt'):continue
            path=Path(w['path']);owner=s.folder/'attempts'/a['id']/'workspace'
            if path.is_symlink() or path.resolve()!=owner.resolve():raise ValueError('工作区不属于该执行实例')
            if a['id'] in s.workers.live or a['id'] in s.scheduler.leases:raise ValueError('执行资源尚未释放')
            p=a.get('process',{})
            if any(i.get('identity') and i.get('pid') and process_identity(i['pid'])==i['identity'] for i in [p,*p.get('children',[])]):raise ValueError('旧进程仍然存活')
            if w['kind']!='git':
                archive=artifact.get('archive')
                if not archive or not Path(archive).is_file() or hashlib.sha256(Path(archive).read_bytes()).hexdigest()!=artifact.get('archiveSha256'):
                    raise ValueError('快照成果缺少完整导出，不能清理以免丢失文件')
            else:
                patch=artifact.get('patch');expected=a.get('verification',{}).get('artifactSha256')
                if not patch or not Path(patch).is_file() or expected and hashlib.sha256(Path(patch).read_bytes()).hexdigest()!=expected:
                    raise ValueError('Git 成果补丁缺失或校验失败，不能清理以免丢失文件')
            records.append({'attemptId':a['id'],'path':str(path),'workspace':w})
        token=hashlib.sha256(json.dumps(records,sort_keys=True).encode()).hexdigest()
        if action=='cleanup/preview':return {'workspaces':records,'previewToken':token,'retains':['transcript','patch','verification','usage','handoff']}
        if params.get('previewToken')!=token or params.get('confirmed') is not True:raise ValueError('请预览并确认要清理的工作区')
        cleaned=[]
        for record in records:
            workspace.cleanup(record['workspace']);cleaned.append(record['attemptId'])
            def mark(d):
                for task in d['tasks']+d.get('planningHistory',[]):
                    for a in task.get('attempts',[]):
                        if a['id']==record['attemptId']:a.setdefault('artifact',{})['cleanedAt']=time.time()
                    if task.get('attempts') and task['attempts'][-1]['id']==record['attemptId']:task.setdefault('artifact',{})['cleanedAt']=time.time()
            s.store.update(key,mark)
        s.changed(key);return {'cleanedAttemptIds':cleaned,'workflow':s.view(s.store.read(key))}
