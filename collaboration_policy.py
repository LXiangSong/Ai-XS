"""Validated workflow policy, deterministic handoff and delivery evidence."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import time


def budget(value=None):
    value=value or {};result={}
    if not isinstance(value,dict):raise ValueError('预算格式无效')
    for key,default,maximum in [('maxAttempts',96,512),('maxRuntimeSeconds',3600,86400),('maxReportedTokens',0,100000000)]:
        number=value.get(key,default)
        if type(number) is not int or number<0 or number>maximum or key!='maxReportedTokens' and number==0:raise ValueError('预算数值无效：'+key)
        result[key]=number
    return result


def validation(value=None):
    value=value or {}
    if not isinstance(value,dict):raise ValueError('验收配置无效')
    outputs=value.get('outputs',[]);checks=value.get('checks',[])
    if not isinstance(outputs,list) or len(outputs)>100 or any(not isinstance(p,str) or not p or PurePosixPath(p).is_absolute() or '..' in PurePosixPath(p).parts for p in outputs):raise ValueError('验收产物须为工作区相对路径')
    if not isinstance(checks,list) or len(checks)>10:raise ValueError('每个任务最多配置 10 项验证命令')
    for args in checks:
        if not isinstance(args,list) or not 1<=len(args)<=50 or any(not isinstance(a,str) or len(a)>2000 or '\0' in a for a in args):raise ValueError('验证命令须为参数数组')
    timeout=value.get('timeoutSeconds',60)
    if type(timeout) is not int or not 1<=timeout<=120:raise ValueError('验证超时须为 1 到 120 秒')
    return {'outputs':outputs,'checks':checks,'timeoutSeconds':timeout}


def evidence_digest(artifact):
    path=artifact.get('patch') or artifact.get('archive')
    return hashlib.sha256(Path(path).read_bytes()).hexdigest() if path and Path(path).is_file() else None


def verify(task, folder, result, dependencies, cancelled=None):
    started=time.time();rules=validation(task.get('validation'));root=Path(folder).resolve();entries=[]
    was_cancelled=False
    for args in rules['checks']:
        if cancelled and cancelled():was_cancelled=True;entries.append({'kind':'command','argv':args,'passed':False,'cancelled':True});break
        try:
            # Temporary files bound captured output without waiting on a pipe held by a child.
            import tempfile,os,signal
            with tempfile.TemporaryFile() as log:
                child=subprocess.Popen(args,cwd=root,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
                timedout=False;deadline=time.monotonic()+rules['timeoutSeconds']
                try:
                    while child.poll() is None:
                        if cancelled and cancelled():was_cancelled=True;break
                        remaining=deadline-time.monotonic()
                        if remaining<=0:timedout=True;break
                        try:child.wait(timeout=min(.2,remaining))
                        except subprocess.TimeoutExpired:pass
                finally:
                    try:os.killpg(child.pid,signal.SIGKILL)
                    except ProcessLookupError:pass
                    child.wait(timeout=5)
                size=log.tell();log.seek(max(0,size-16000));output=log.read().decode(errors='replace')
            entries.append({'kind':'command','argv':args,'passed':not timedout and not was_cancelled and child.returncode==0,'exitCode':child.returncode,'timedOut':timedout,'cancelled':was_cancelled,'output':output})
            if was_cancelled:break
        except (OSError,subprocess.TimeoutExpired) as error:entries.append({'kind':'command','argv':args,'passed':False,'error':str(error)})
    # Commands may generate or remove outputs; record evidence from the delivered files.
    for output in rules['outputs']:
        path=root/output;resolved=path.resolve()
        ok=resolved.is_relative_to(root) and path.is_file()
        entries.append({'kind':'file','path':output,'passed':ok,'sha256':hashlib.sha256(path.read_bytes()).hexdigest() if ok else None})
    review=None
    if task.get('kind')=='review':
        text=result.strip()
        try:
            if text.startswith('```'):text='\n'.join(text.splitlines()[1:-1])
            document=json.loads(text);review=document['review']
            if type(review.get('approved')) is not bool or not isinstance(review.get('findings',[]),list):raise ValueError()
            entries.append({'kind':'review','passed':review['approved'],'findings':review.get('findings',[])})
        except (ValueError,KeyError,TypeError,AttributeError):entries.append({'kind':'review','passed':False,'error':'审核未返回约定的 review.approved 与 findings JSON，不能当作审核通过'})
    return {'status':'failed' if any(not e['passed'] for e in entries) else 'passed' if entries else 'manual_required',
            'startedAt':started,'finishedAt':time.time(),'checks':entries,'review':review,'cancelled':was_cancelled,
            'dependencies':[{'taskId':t['id'],'attemptId':t.get('attempts',[{}])[-1].get('id') if t.get('attempts') else None,
                             'patchSha256':evidence_digest(t.get('artifact',{}))} for t in dependencies]}


def handoff(doc, task, current_attempt=None):
    """Carry explicit requirements and evidence; never include private reasoning or credentials."""
    attempts=[a for a in task.get('attempts',[]) if a['id']!=current_attempt];last=attempts[-1] if attempts else {}
    messages=[m for m in doc.get('messages',[]) if (not m.get('taskId') or m['taskId']==task['id']) and (not m.get('taskIds') or task['id'] in m['taskIds'])]
    content={'version':1,'taskId':task['id'],'goal':task['goal'],'acceptance':task['acceptance'],
        'previousAttemptId':last.get('id'),'previousStatus':last.get('status'),'result':task.get('result','')[-20000:],
        'error':task.get('error',''),'artifact':task.get('artifact',{}),'verification':task.get('verification'),
        'requirements':[{'id':m['id'],'text':m['text'],'at':m['at'],**({'input':m['input']} if m.get('input') else {})} for m in messages],
        'uncertainOperations':last.get('status')=='needs_reconciliation',
        'continuation':'重新检查产物后继续；已执行的外部操作不能盲目重放'}
    return content


def metrics(doc):
    attempts=[a for t in doc['tasks']+doc.get('planningHistory',[]) for a in t.get('attempts',[])]
    tokens=0;partial=False
    for a in attempts:
        usage=(a.get('usage') or {}).get('tokenUsage') or {};total=usage.get('total') or {}
        value=total.get('totalTokens')
        if not isinstance(value,(int,float)):value=(total.get('inputTokens') or 0)+(total.get('outputTokens') or 0)
        tokens+=max(0,int(value));partial=partial or not total or bool((a.get('usage') or {}).get('totalsPartial'))
    began=doc.get('startedAt') or min((a['startedAt'] for a in attempts),default=None)
    end=time.time()
    if doc.get('status') in ('awaiting_acceptance','completed','cancelled','partial_failure') and not any(t['status'] in ('starting','running','waiting_input','stopping') for t in doc['tasks']):
        end=max((a.get('finishedAt') or a.get('startedAt') or began or 0 for a in attempts),default=began or 0)
    paused=doc.get('executionPausedSeconds',0)
    if doc.get('executionPauseStartedAt'):paused+=max(0,end-doc['executionPauseStartedAt'])
    elapsed=max(0,end-began-paused) if began else 0
    limits=budget(doc.get('budget'));reason=''
    if limits['maxReportedTokens'] and tokens>=limits['maxReportedTokens']:reason='reported_tokens'
    elif elapsed>=limits['maxRuntimeSeconds']:reason='runtime'
    return {'attempts':len(attempts),'reportedTokens':tokens,'tokensPartial':partial,'elapsedSeconds':int(elapsed),
            'limits':limits,'stopReason':reason,'canDispatch':not reason and len(attempts)<limits['maxAttempts']}


def delivery(doc):
    tasks=doc['tasks'];verified=sum(t.get('verification',{}).get('status')=='passed' for t in tasks)
    required=[t['id'] for t in tasks if t['status']=='succeeded' and t.get('verification',{}).get('status')!='passed']
    return {'status':'accepted' if doc.get('accepted') else 'verification_failed' if any(t.get('verification',{}).get('status')=='failed' for t in tasks)
            else 'awaiting_acceptance' if all(t['status']=='succeeded' for t in tasks) else 'executing',
            'verifiedTasks':verified,'totalTasks':len(tasks),'manualRequiredTaskIds':required,'acceptance':doc.get('acceptanceReceipt')}
