"""Collaboration is an execution mode of an existing shared conversation."""
import copy
import json
import time
from collaboration_store import ACTIVE, ident
from execution_profiles import participant
from collaboration_transcript import conversation, AttemptTranscript
from conversation_usage import execution_stats
import collaboration_policy as policy


class ConversationSessions:
    def __init__(self, service):self.service=service

    def handle(self, action, params):
        s=self.service;key=str(params.get('threadId') or '')
        chat=s.chats.adopt(key)
        if not chat:raise ValueError('会话不存在')
        if action=='session/config':
            mode=params.get('mode',chat.get('executionMode','independent'))
            if mode not in ('independent','collaboration'):raise ValueError('会话模式无效')
            people=None
            if 'participants' in params:
                people=[dict(participant(p),device=bool(p.get('device')),browser=bool(p.get('browser'))) for p in params['participants']]
                if not 2<=len(people)<=8:raise ValueError('协作需要 2 到 8 位成员，可使用同一 Agent 的不同模型')
                if len({p['id'] for p in people})!=len(people) or any(not p['id'] for p in people):raise ValueError('成员编号重复或为空')
            with s.chats.lock:
                if mode=='collaboration':s.chats._idle(chat)
                configured=people if people is not None else chat.get('collaborationParticipants',[])
                if mode=='collaboration' and len(configured)<2:raise ValueError('请先配置至少两位协作成员')
                chat['executionMode']=mode
                if people is not None:chat['collaborationParticipants']=people
                if 'summarize' in params:chat['collaborationSummarize']=bool(params['summarize'])
                if 'template' in params:
                    if params['template'] not in ('auto','parallel','development','research'):raise ValueError('协作模板无效')
                    chat['collaborationTemplate']=params['template']
                if 'budget' in params:chat['collaborationBudget']=policy.budget(params['budget'])
                s.chats._save(chat)
            return {'thread':s.chats.view(chat)}
        if action=='session/submit':return self.submit(chat,params)
        if action!='session/read':raise ValueError('未知会话模式操作')
        # Old collaboration data is folded back into the original conversation.
        # Never create a second conversation or delete user history for migration.
        docs=[d for d in s.store.listing(key) if d['id'] not in chat.get('collaborationExcludedWorkflows',[])]
        for doc in docs:
            if not doc.get('inline'):
                doc,_=s.store.update(doc['id'],lambda d:d.update(inline=True))
            self.sync(doc['id'])
        return {'thread':s.chats.view(chat),'workflows':[s.view(s.store.read(d['id'])) for d in docs]}

    def submit(self, chat, params, from_queue=False):
        s=self.service;request=params.get('clientRequestId');previous=s.store.by_request(request)
        if previous:
            if previous['conversationId']!=chat['id']:raise ValueError('提交编号已属于另一条会话')
            self.sync(previous['id'])
            return {'workflow':s.view(previous),'thread':s.chats.view(chat)}
        if not from_queue:
            if s.queue.busy(chat):return s.queue.submit(chat,params,'collaboration')
            if s.queue.pending(chat):
                head=next((e for e in chat.get('messageQueue',[]) if e['status'] in ('queued','dispatching','including','needs_reconciliation','failed')),None)
                if getattr(s,'execution',None) and not (head and head.get('executionResume')):
                    try:s.execution.accept_new_input(chat)
                    except ValueError:pass
                if s.queue.busy(chat) or s.queue.pending(chat):
                    return s.queue.submit(chat,params,'collaboration')
        with s.chats.lock:
            s.chats._idle(chat)
            if chat.get('executionMode')!='collaboration':raise ValueError('当前会话不是协作模式')
            if chat.get('archived'):raise ValueError('请先恢复已归档的会话')
            people=copy.deepcopy(chat.get('collaborationParticipants',[]))
            if len(people)<2:raise ValueError('请先配置协作成员')
            context=s.chats.handoff(chat)[0][-30000:]
        for d in s.store.listing(chat['id']):
            if any(t['status'] in ACTIVE for t in d['tasks']) or d['status'] in ('running','paused') and any(t['status'] in {'blocked','queued'} for t in d['tasks']):
                raise ValueError('当前协作尚未结束，请先等待、继续或停止')
        parts=copy.deepcopy(params.get('input',[]))
        if not isinstance(parts,list) or not 1<=len(parts)<=32:raise ValueError('消息内容无效')
        for part in parts:
            if not isinstance(part,dict) or part.get('type') not in ('text','localImage'):raise ValueError('协作消息暂不支持该附件类型')
            field='text' if part['type']=='text' else 'path'
            if not isinstance(part.get(field),str) or not part[field].strip():raise ValueError('消息内容无效')
        text='\n'.join(p['text'] for p in parts if p['type']=='text').strip() or '根据附件完成任务'
        if len(text)>28000:raise ValueError('消息过长，请缩短后重试')
        roles='\n'.join(p['name']+'：'+(p['role'] or '从自己的专长完成独立部分') for p in people)
        template=params.get('template',chat.get('collaborationTemplate','auto'))
        if template not in ('auto','parallel','development','research'):raise ValueError('协作模板无效')
        # Existing saved roles determine the auto template; explicit parallel keeps old semantics.
        def reviewer(p):return any(word in p.get('role','').lower() for word in ('审核','复核','审查','review'))
        staged=template=='development' or template=='auto' and any(reviewer(p) for p in people)
        reviewers=[p for p in people if reviewer(p)] if staged else []
        if staged and not reviewers:reviewers=people[-1:]
        workers=[p for p in people if p not in reviewers]
        if not workers:workers=people[:1];reviewers=people[1:]
        tasks=[]
        for p in workers:
            tasks.append({'id':ident(),'title':p['name']+' · '+(p['role'] or '分工执行')[:40],
                'goal':'用户任务：'+text+'\n团队分工：\n'+roles+'\n你只完成自己职责范围内的部分，与其他成员形成互补。',
                'acceptance':'遵守用户指定的输出格式和限制；用户未限制输出时，给出职责范围内的成果、依据及尚未解决的问题。',
                'participantId':p['id'],'dependencies':[],'device':bool(p.get('device')),'browser':bool(p.get('browser')),
                'kind':'research' if template=='research' else 'work'})
        implementation=[t['id'] for t in tasks]
        for p in reviewers:
            tasks.append({'id':ident(),'title':p['name']+' · 审核成果','goal':'在实现完成后审核依赖成果，按用户要求检查正确性、测试和未解决风险。',
                'acceptance':'审核确切产物，返回 review.approved 与 findings JSON；不修改待审文件。',
                'participantId':p['id'],'dependencies':implementation,'reviewTargets':implementation,'kind':'review',
                'device':bool(p.get('device')),'browser':bool(p.get('browser'))})
        if chat.get('collaborationSummarize',True):
            tasks.append({'id':ident(),'title':'汇总回复','goal':'综合团队依赖成果，回答用户：'+text+'。标明分歧、验证情况与未完成项，不重复已执行操作。',
                'acceptance':'遵守用户指定的输出格式，给出一致、可核对的最终答复。','participantId':people[0]['id'],'dependencies':[t['id'] for t in tasks],'kind':'summary'})
        participants,tasks,lead=s._plan({'participants':people,'tasks':tasks,'leadParticipantId':people[0]['id']})
        doc=s.store.create({'conversationId':chat['id'],'cwd':chat['cwd'],'title':text[:80],'goal':text,
            'status':'running','pauseReasons':[],'participants':participants,'tasks':tasks,'leadParticipantId':lead,
            'accepted':False,'mode':'collaboration','inline':True,'input':parts,'contextSnapshot':context,
            'template':template,'budget':policy.budget(params.get('budget',chat.get('collaborationBudget'))),
            'rework':{'auto':False,'maxRounds':2},'startedAt':time.time()},request)
        self.sync(doc['id']);s.changed(doc['id'])
        return {'workflow':s.view(doc),'thread':s.chats.view(chat)}

    def sync(self, key):
        s=self.service;doc=s.store.read(key)
        # Statistics include every attempt, independent of the bounded visible feed.
        with s.chats.lock:
            chat=s.chats.adopt(doc['conversationId']);changed=False
            for task in doc['tasks']+doc.get('planningHistory',[]):
                for attempt in task.get('attempts',[]):
                    config=attempt.get('config')
                    if not isinstance(config,dict):
                        try:config=json.loads((s.folder/'attempts'/attempt['id']/'spec.json').read_text())['config']
                        except (OSError,ValueError,KeyError):config={'engine':attempt.get('engine','unknown'),'model':None}
                    stats=copy.deepcopy(attempt.get('stats'))
                    if not isinstance(stats,dict):
                        path=s.folder/'attempts'/attempt['id']/'transcript.json'
                        snap=AttemptTranscript(path).snapshot() if path.exists() else {'items':[]}
                        stats=execution_stats([{'type':'userMessage'}]+snap['items'],attempt['status'],attempt.get('startedAt'),attempt.get('finishedAt'))
                        stats['partial']=stats.get('partial',False) or not path.exists() or bool(snap.get('omittedItems') or snap.get('historyError'))
                    # Status may change on restart/stop without another worker event.
                    stats['active']=int(attempt['status'] in ACTIVE);stats['statuses']={attempt['status']:1}
                    changed=s.chats.record_usage(chat,config.get('engine',''),'collaboration:'+attempt['id'],attempt.get('usage') or {},
                        model=config.get('model'),metadata={'mode':'collaboration','workflowId':key,'taskId':task['id'],
                            'attemptId':attempt['id'],'participantId':config.get('id',task['participantId']),
                            'participant':config.get('name',''),'status':attempt['status'],'startedAt':attempt.get('startedAt'),
                            'finishedAt':attempt.get('finishedAt'),'stats':stats}) or changed
            if changed:s.chats._save(chat)
        if not doc.get('inline'):return
        # Events are already redacted; internal generated prompts are excluded.
        with s.lock:live=dict(s.transcripts)
        feed=conversation(doc,s.folder,live,{'limit':100})
        items=[{'id':'user-'+key,'type':'userMessage','content':copy.deepcopy(doc.get('input') or [{'type':'text','text':doc['goal']}])}]
        for entry in feed['entries']:
            if entry['kind']=='user':
                items.append({'id':entry['id'],'type':'userMessage','content':entry.get('input') or [{'type':'text','text':entry.get('text','')}]})
                continue
            item=copy.deepcopy(entry.get('item') or {'type':'agentMessage','text':entry.get('text','')})
            item.update(id=entry['id'],engine=entry.get('engine',''),model=entry.get('model',''),participant=entry.get('participant',''),
                attemptId=entry.get('attemptId',''),participantId=entry.get('participantId',''),
                taskId=entry.get('taskId',''),taskTitle=entry.get('taskTitle',''),attemptNumber=entry.get('attemptNumber',1),
                attemptStatus=entry.get('attemptStatus',''),collaborationError=entry['kind']=='error')
            items.append(item)
        statuses=[t['status'] for t in doc['tasks']]
        active=sum(st in ACTIVE for st in statuses)
        turn={'id':'collaboration-session-'+key,'status':'completed','engine':'collaboration','workflowId':key,
            'collaborationStatus':'stopping' if doc['status']=='cancelled' and active else doc['status'],'items':items,'createdAt':doc['createdAt'],
            'collaborationActive':active,'collaborationCompleted':statuses.count('succeeded'),'collaborationTotal':len(statuses),
            'collaborationRevisions':feed['revisions'],
            'historyHasMore':feed['hasMore'],'historyBefore':feed['before'],'historyOmittedItems':feed.get('omittedItems',0),
            'delivery':policy.delivery(doc),'budgetUsage':policy.metrics(doc),
            'historyPartial':feed['historyPartial'] or feed['hasMore'],
            'progress':{'detail':' · '.join(t['title']+'：'+(t.get('progress',{}).get('detail') or t.get('waitReason') or t['status'])
                        for t in doc['tasks'] if t['status'] in ACTIVE|{'queued'})}}
        with s.chats.lock:
            chat=s.chats.adopt(doc['conversationId'])
            if key in chat.get('collaborationExcludedWorkflows',[]):return
            existing=next((t for t in chat['turns'] if t['id']==turn['id']),None)
            if existing is not None and existing.get('legacySummary'):turn['legacySummary']=existing['legacySummary']
            if existing==turn:return
            if existing is not None:existing.clear();existing.update(turn)
            else:
                legacy=[i for i,t in enumerate(chat['turns']) if t['id'].startswith('collaboration-'+key+'-')]
                if legacy:
                    turn['legacySummary']=copy.deepcopy(chat['turns'][legacy[-1]])
                    chat['turns'][legacy[-1]]=turn
                else:chat['turns'].append(turn)
            if not chat['preview']:chat['preview']=doc['goal'][:100]
            if not s.chats._running(chat):chat['binding']=None
            chat['updatedAt']=time.time();s.chats._save(chat)
