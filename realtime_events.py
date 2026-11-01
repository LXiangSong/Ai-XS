"""Provider-independent live item updates for shared and collaboration conversations.

Only user-visible protocol fields are projected here; terminal input and internal
provider metadata never become transcript text. Full item snapshots replace text,
whereas delta events append it exactly once.
"""
import copy
import json

ACTIVE = ('inProgress', 'in_progress', 'pending', 'running', 'starting')


def item(items, ident, kind):
    found = next((i for i in items if i.get('id') == ident), None)
    if found is None:
        found = {'id': ident, 'type': kind, 'status': 'inProgress'}
        items.append(found)
    return found


def apply(turn, method, params):
    applied=_apply(turn,method,params)
    if applied and params.get('nativeChild'):
        ident=params.get('itemId') or (params.get('item') or {}).get('id')
        target=next((i for i in turn.get('items',[]) if i.get('id')==ident),None)
        if target:
            target.update({k:params[k] for k in ('nativeChild','sourceEngine','sourceThreadId','sourceTurnId','sourceModel','parentToolUseId') if k in params})
            if target.get('type') in ('agentMessage','assistantMessage'):target['type']='subagentMessage'
    return applied


def _apply(turn, method, params):
    items = turn.setdefault('items', [])
    if method in ('item/started', 'item/completed'):
        raw = params.get('item') or {}
        if not raw.get('id'):return False
        target = item(items, raw['id'], raw.get('type', 'unknown'))
        fields=copy.deepcopy(raw)
        for key in ('aggregatedOutput','text','content','summary'):
            if fields.get(key,'') is None and target.get(key) is not None:fields.pop(key,None)
        target.update(fields)
        if method == 'item/completed' and target.get('status') in ACTIVE:
            target['status'] = 'completed'
        if method=='item/completed' and (target.get('success') is False or target.get('type')=='commandExecution' and target.get('exitCode') not in (None,0)):
            target['status']='failed'
        return True
    ident = params.get('itemId')
    if method in ('item/agentMessage/delta', 'item/plan/delta') and ident:
        target = item(items, ident, 'plan' if '/plan/' in method else 'agentMessage')
        target['text'] = (target.get('text') or '') + (params.get('delta') or '')
    elif method.startswith('item/reasoning/') and ident:
        target = item(items, ident, 'reasoning')
        summary = 'summary' in method
        key = 'summary' if summary else 'content'
        values = target.get(key) or []
        target[key] = values
        index = max(0, min(100, int(params.get('summaryIndex' if summary else 'contentIndex', 0))))
        while len(values) <= index:values.append('')
        old = values[index].get('text', '') if isinstance(values[index], dict) else str(values[index] or '')
        values[index] = (old or '') + (params.get('delta') or '')
    elif method in ('item/commandExecution/outputDelta', 'item/fileChange/outputDelta') and ident:
        target = item(items, ident, 'commandExecution' if 'commandExecution' in method else 'fileChange')
        target['aggregatedOutput'] = (target.get('aggregatedOutput') or '') + (params.get('delta') or '')
    elif method == 'item/fileChange/patchUpdated' and ident:
        item(items, ident, 'fileChange')['changes'] = copy.deepcopy(params.get('changes', []))
    elif method == 'item/mcpToolCall/progress' and ident:
        item(items, ident, 'mcpToolCall')['progressMessage'] = params.get('message', '')
    elif method == 'item/commandExecution/terminalInteraction' and ident:
        item(items, ident, 'commandExecution')['progressMessage'] = '终端输入已发送'
    elif method in ('item/autoApprovalReview/started', 'item/autoApprovalReview/completed'):
        review = params.get('review') or {}
        ident = params.get('reviewId')
        if not ident:return False
        item(items, 'review:' + ident, 'runtimeStatus').update(tool='自动审批检查',
            progressMessage=review.get('rationale') or '',
            status='inProgress' if method.endswith('/started') else 'completed')
    elif method == 'turn/plan/updated':
        target = item(items, 'aixs-turn-plan', 'plan')
        steps = params.get('plan', [])
        labels = {'pending': '待执行', 'inProgress': '进行中', 'completed': '已完成'}
        target['text'] = '\n'.join(filter(None, [params.get('explanation')] + [
            labels.get(s.get('status'), s.get('status', '')) + ' · ' + s.get('step', '') for s in steps]))
        target['status'] = 'completed' if steps and all(s.get('status') == 'completed' for s in steps) else 'inProgress'
    elif method == 'turn/diff/updated':
        item(items, 'aixs-turn-diff', 'turnDiff').update(changes=params.get('diff', ''), tool='累计修改')
    elif method in ('hook/started', 'hook/completed'):
        run = params.get('run') or {}
        if not run.get('id'):return False
        item(items, 'hook:' + run['id'], 'hook').update(
            tool=str(run.get('eventName') or 'Hook'), progressMessage=run.get('statusMessage') or '',
            result=copy.deepcopy(run.get('entries', [])), status='inProgress' if method.endswith('/started') else
            'failed' if run.get('status') in ('failed', 'blocked') else 'completed')
    elif method == 'error':
        retry = params.get('willRetry', False)
        item(items, 'aixs-runtime-error', 'runtimeStatus').update(
            tool='正在重试' if retry else '执行错误', result=copy.deepcopy(params.get('error')),
            status='inProgress' if retry else 'failed')
    elif method == 'model/rerouted':
        item(items, 'aixs-model-reroute', 'runtimeStatus').update(tool='模型已切换', status='completed',
            text=str(params.get('fromModel', '')) + ' → ' + str(params.get('toModel', '')))
    elif method in ('warning','guardianWarning','deprecationNotice','configWarning','modelProvider/authRecoveryStarted','modelProvider/authRecoveryCompleted',
                    'model/verification','model/safetyBuffering/updated','autoApprovalReview/strictReviewRequired','mcpServer/startupStatus/updated'):
        labels={'warning':'运行提示','guardianWarning':'审批提示','deprecationNotice':'兼容性提示','configWarning':'配置提示',
            'modelProvider/authRecoveryStarted':'正在恢复 API 认证','modelProvider/authRecoveryCompleted':'API 认证已恢复',
            'model/verification':'模型验证','model/safetyBuffering/updated':'内容审核',
            'autoApprovalReview/strictReviewRequired':'等待严格审批','mcpServer/startupStatus/updated':'MCP 服务'}
        ident='runtime:'+method+(':'+str(params.get('name')) if params.get('name') else '')
        text=params.get('message') or params.get('summary') or params.get('details') or ''
        status='inProgress' if method.endswith('Started') or method.endswith('strictReviewRequired') else 'completed'
        if method=='model/safetyBuffering/updated':
            status='inProgress' if params.get('showBufferingUi') else 'completed';text=' · '.join(params.get('reasons') or [])
        elif method=='model/verification':text=json.dumps(params.get('verifications') or [],ensure_ascii=False)
        elif method=='mcpServer/startupStatus/updated':
            status={'starting':'inProgress','failed':'failed','cancelled':'interrupted'}.get(params.get('status'),'completed')
            text=str(params.get('name',''))+' · '+str(params.get('error') or params.get('status') or '')
        item(items,ident,'runtimeStatus').update(tool=labels[method],text=text,status=status)
    else:return False
    return True


def settle(turn, status):
    """An interrupted/failed turn must not leave operation cards spinning forever."""
    for entry in turn.get('items', []):
        if entry.get('status') in ACTIVE:
            entry['status'] = 'completed' if status in ('completed', 'succeeded') else status
