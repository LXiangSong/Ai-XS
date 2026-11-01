"""Model-agnostic accounting for one conversation and its isolated executions.

Cumulative native counters are differenced once per source. Context windows are
snapshots, never billing counters and never summed across parallel executions.
"""
import copy
import hashlib
import math
import time

ACTIVE={'starting','running','waiting_input','stopping','inProgress'}
ASSISTANT={'agentMessage','assistantMessage','subagentMessage'}
TOOLS={'commandExecution','dynamicToolCall','mcpToolCall','fileChange','webSearch','collabAgentToolCall','imageView','imageGeneration','sleep'}


def counters(raw):
    if not isinstance(raw,dict):return {}
    result={k:v for k,v in raw.items() if isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) and v>=0 and k.endswith('Tokens')}
    if 'totalTokens' not in result and 'inputTokens' in result and 'outputTokens' in result:
        result['totalTokens']=result['inputTokens']+result['outputTokens']
    return result


def add(target, value):
    for key,amount in counters(value).items():target[key]=target.get(key,0)+amount


def model_id(value):return value if isinstance(value,str) and value.strip() else None


def group_id(engine, model):
    return hashlib.sha256((engine+'\0'+(model or '')).encode()).hexdigest()[:24]


def source_model(doc, engine, source):
    binding=doc.get('binding') or {}
    if binding.get('threadId')==source and model_id(binding.get('model')):return binding['model']
    child=next((c for c in doc.get('children',[]) if c.get('threadId')==source and c.get('engine')==engine),{})
    if model_id(child.get('model')):return child['model']
    models={model_id(t.get('model')) for t in doc.get('turns',[]) if t.get('engine')==engine and str(t.get('id','')).startswith(engine+':'+source+':')}
    models.discard(None)
    return next(iter(models)) if len(models)==1 else None


def migrate_models(row):
    """Legacy totals with no exact model stay explicitly unattributed."""
    if isinstance(row.get('modelSnapshots'),dict):return copy.deepcopy(row['modelSnapshots'])
    if not row:return {}
    model=model_id(row.get('model'));tokens=row.get('tokenUsage') or {}
    return {model or '':{'model':model,'total':counters(tokens.get('total')),'last':counters(tokens.get('last')),
        'modelContextWindow':tokens.get('modelContextWindow'),'recordedAt':row.get('recordedAt'),
        'updatedAt':row.get('updatedAt',0),'contextStale':bool(row.get('contextStale'))}}


def record(doc, engine, source, value, model=None, metadata=None):
    if not isinstance(value,dict):value={}
    snapshots=doc.setdefault('usageSnapshots',{});old=copy.deepcopy(snapshots.get(source) or {})
    explicit=model_id(model) or model_id(value.get('model'))
    current=explicit or source_model(doc,engine,source) or model_id(old.get('model'))
    models=migrate_models(old);row=copy.deepcopy(old)
    # Only approved public metadata is retained, never provider keys or prompts.
    for key in ('mode','workflowId','taskId','attemptId','participantId','participant','status','startedAt','finishedAt','stats'):
        if metadata is not None and key in metadata:row[key]=copy.deepcopy(metadata[key])
    tokens=value.get('tokenUsage');previous=(old.get('tokenUsage') or {}).get('total',{})
    if isinstance(tokens,dict):
        native_models=tokens.get('byModel')
        if isinstance(native_models,list) and native_models:
            # A native breakdown covers this entire source, including its past
            # models. Replace provisional attribution instead of adding it twice.
            prior_models=models;models={}
            for entry in native_models:
                if not isinstance(entry,dict):continue
                name=model_id(entry.get('model'))
                slot=models.setdefault(name or '',copy.deepcopy(prior_models.get(name or '') or {'model':name,'total':{}}))
                before=copy.deepcopy(slot)
                incoming=counters(entry.get('total'))
                high=counters(slot.get('total'))
                for key,amount in incoming.items():
                    if amount<high.get(key,0):row['counterRegression']=True
                    high[key]=max(high.get(key,0),amount)
                slot['total']=high
                for key in ('last','modelContextWindow','contextStale'):
                    if key in entry:slot[key]=copy.deepcopy(entry[key])
                # Baseline entries for retired models must retain the time at
                # which that context was actually reported.
                if slot!=before or not slot.get('updatedAt'):
                    slot['recordedAt']=entry.get('recordedAt',value.get('recordedAt'))
                    slot['updatedAt']=entry.get('updatedAt',value.get('updatedAt',time.time()))
        else:
            attribution=current
            # A first aggregate read after an upgrade cannot reconstruct a
            # mixed-model thread's past bill. Preserve it as unknown instead
            # of assigning every old model's tokens to the latest selection.
            known={model_id(t.get('model')) for t in doc.get('turns',[]) if t.get('engine')==engine
                and str(t.get('id','')).startswith(engine+':'+source+':')}
            known.discard(None)
            if not counters(previous) and len(known)>1:attribution=None
            slot=models.setdefault(attribution or '',{'model':attribution,'total':{}})
            increment={}
            for key,amount in counters(tokens.get('total')).items():
                prior=counters(previous).get(key,0)
                if amount<prior:
                    row['counterRegression']=True
                    continue
                increment[key]=amount-prior
            add(slot['total'],increment)
            # Context belongs to the reporting model even when an old bill
            # cannot be attributed to that model.
            slot=models.setdefault(current or '',{'model':current,'total':{}})
            if 'last' in tokens:slot['last']=counters(tokens['last'])
            if 'modelContextWindow' in tokens:slot['modelContextWindow']=tokens['modelContextWindow']
            slot['recordedAt']=value.get('recordedAt',slot.get('recordedAt'))
            slot['contextStale']=bool(value.get('contextStale',slot.get('contextStale',False)))
            slot['updatedAt']=value.get('updatedAt',time.time())
        merged=copy.deepcopy(old.get('tokenUsage') or {})
        merged.update(copy.deepcopy(tokens))
        # A context-only event cannot erase previously reported cumulative billing.
        high=counters(previous)
        for key,amount in counters(tokens.get('total')).items():high[key]=max(high.get(key,0),amount)
        merged['total']=high;row['tokenUsage']=merged
    row.update(engine=engine,model=current,modelSnapshots=models)
    if model_id(value.get('model')):row['contextModel']=value['model']
    for key in ('recordedAt','contextStale','compactedAt','totalsPartial','nativeUsageRecovered'):
        if key in value:row[key]=copy.deepcopy(value[key])
    def compare(v):
        if isinstance(v,dict):return {k:compare(x) for k,x in v.items() if k!='updatedAt'}
        if isinstance(v,list):return [compare(x) for x in v]
        return v
    if compare(row)==compare(old):return False
    row['updatedAt']=value.get('updatedAt',time.time());snapshots[source]=row
    return True


def execution_stats(items, status, started=None, finished=None, duration=None):
    values=[i for i in items if isinstance(i,dict)]
    user=sum(i.get('type')=='userMessage' for i in values)
    assistant=sum(i.get('type') in ASSISTANT for i in values)
    stats={'executions':1,'turns':1,'userMessages':user,'assistantMessages':assistant,'messages':user+assistant,
        'toolCalls':sum(i.get('type') in TOOLS for i in values),'reasoningItems':sum(i.get('type')=='reasoning' for i in values),
        'durationMs':max(0,int((finished-started)*1000)) if isinstance(started,(int,float)) and isinstance(finished,(int,float)) else 0,
        'durationReported':isinstance(started,(int,float)) and isinstance(finished,(int,float)),
        'active':int(status in ACTIVE),'statuses':{status or 'unknown':1}}
    stats['partial']=not stats['durationReported']
    if isinstance(duration,(int,float)) and not isinstance(duration,bool) and math.isfinite(duration) and duration>=0:
        stats.update(durationMs=duration,durationReported=True,partial=False)
    return stats


def blank_stats():
    return {'executions':0,'turns':0,'userMessages':0,'assistantMessages':0,'messages':0,'toolCalls':0,'reasoningItems':0,'durationMs':0,'active':0,'statuses':{},'partial':False}


def execution_models(items, engine, model, status, started=None, finished=None, children=None, duration=None):
    """Attribute native child operations once, retaining unknown models explicitly."""
    children=children or {};groups={}
    def append(which_engine,which_model,stats):
        slot=groups.setdefault((which_engine,model_id(which_model)),blank_stats());merge_stats(slot,stats)
    own=execution_stats([i for i in items if not i.get('nativeChild')],status,started,finished,duration)
    append(engine,model,own)
    completed=set()
    for key,child in children.items():
        child_turn=child.get('turn') or {};source=child.get('sourceThreadId') or key.rsplit(':',1)[0]
        source_turn=str(child.get('sourceTurnId') or child_turn.get('nativeTurnId') or child_turn.get('id') or key.rsplit(':',1)[-1])
        completed.add((source,source_turn))
        append(child.get('engine') or engine,child.get('model'),child.get('stats') or execution_stats(child_turn.get('items',[]),child_turn.get('status'),child_turn.get('startedAt'),child_turn.get('completedAt'),child_turn.get('durationMs')))
    pending={}
    source_models={}
    for entry in items:
        if entry.get('nativeChild') and model_id(entry.get('sourceModel')):
            source_models.setdefault((entry.get('sourceThreadId'),str(entry.get('sourceTurnId') or '')),set()).add(entry['sourceModel'])
    for entry in items:
        if not entry.get('nativeChild'):continue
        # A thread-scoped preflight notice is visible activity, not another
        # execution. Its real child turn is accounted for separately below.
        if entry.get('type')=='runtimeStatus' and not entry.get('sourceTurnId'):continue
        source=entry.get('sourceThreadId');source_turn=str(entry.get('sourceTurnId') or '')
        if (source,source_turn) in completed:continue
        model_name=entry.get('sourceModel');known=source_models.get((source,source_turn),set())
        if not model_name and len(known)==1:model_name=next(iter(known))
        key=(entry.get('sourceEngine') or engine,model_name,source,source_turn)
        pending.setdefault(key,[]).append(entry)
    for (child_engine,child_model,_,_),values in pending.items():
        append(child_engine,child_model,execution_stats(values,status))
    return [dict(engine=e,model=m,stats=s) for (e,m),s in groups.items()]


def merge_stats(target, stats):
    for key in ('executions','turns','userMessages','assistantMessages','messages','toolCalls','reasoningItems','durationMs','active'):
        value=stats.get(key,0)
        if isinstance(value,(int,float)) and not isinstance(value,bool) and value>=0:target[key]+=value
    for key,value in stats.get('statuses',{}).items():target['statuses'][key]=target['statuses'].get(key,0)+value
    target['partial']=target['partial'] or bool(stats.get('partial'))


def context_snapshot(source, row, snapshot):
    last=counters(snapshot.get('last'));used=last.get('totalTokens')
    window=snapshot.get('modelContextWindow')
    if not isinstance(window,(int,float)) or isinstance(window,bool) or not math.isfinite(window) or window<=0:window=None
    active=row.get('status') in ACTIVE
    value={'sourceId':source,'usedTokens':used,'windowTokens':window,'remainingTokens':max(0,window-used) if window is not None and used is not None else None,
        'ratio':min(1,used/window) if window is not None and used is not None else None,'reported':used is not None,
        'stale':bool(snapshot.get('contextStale')),'active':active,'historical':not active,
        'status':row.get('status','unknown'),'recordedAt':snapshot.get('recordedAt'),
        'updatedAt':snapshot.get('updatedAt',row.get('updatedAt',0))}
    for key in ('mode','workflowId','taskId','attemptId','participantId','participant'):
        if key in row:value[key]=row[key]
    return value


def breakdown(doc):
    groups={};snapshots=doc.get('usageSnapshots') or {};binding=doc.get('binding') or {}
    def get(engine, model):
        model=model_id(model);key=group_id(engine,model)
        if key not in groups:groups[key]={'id':key,'engine':engine,'model':model,'modelKnown':model is not None,
            'isCurrent':False,'isConfigured':False,'isActive':False,'historical':False,'used':False,
            'tokenUsage':{'total':{}},'totalsReported':False,'totalsPartial':False,'contexts':[],'context':None,'stats':blank_stats()}
        return groups[key]
    get(doc['engine'],doc.get('model'))['isCurrent']=True
    for p in doc.get('collaborationParticipants',[]):get(p['engine'],p.get('model'))['isConfigured']=True
    all_stats=blank_stats();native_turns={}
    for turn in doc.get('turns',[]):
        if turn.get('workflowId'):continue
        engine=turn.get('engine',doc['engine'])
        if engine not in ('codex','claude','deepseek'):continue
        model=model_id(turn.get('model'));group=get(engine,model);group['used']=True
        for entry in execution_models(turn.get('items',[]),engine,model,turn.get('status'),turn.get('startedAt'),turn.get('completedAt'),turn.get('nativeChildren'),turn.get('durationMs')):
            target=get(entry['engine'],entry['model']);target['used']=True
            merge_stats(target['stats'],entry['stats']);merge_stats(all_stats,entry['stats'])
        group['isActive']|=turn.get('status') in ACTIVE
        native_turns.setdefault((engine,model),[]).append(turn)
    for source,row in snapshots.items():
        engine=row.get('engine',doc['engine']);models=migrate_models(row)
        if row.get('mode')=='independent':
            turns=[t for t in doc.get('turns',[]) if str(t.get('id','')).startswith(engine+':'+source+':')]
            # Loading a saved conversation marks abandoned native turns as
            # interrupted; its usage snapshots must not remain active forever.
            if turns:row=dict(row,status=turns[-1].get('status','unknown'))
        if not models:models={model_id(row.get('model')) or '':{'model':model_id(row.get('model')),'total':{}}}
        people={p.get('id'):p for p in doc.get('collaborationParticipants',[]) if isinstance(p,dict) and p.get('id')}
        for snapshot in models.values():
            model=model_id(snapshot.get('model'))
            if model is None:
                person=people.get(row.get('participantId')) or people.get(row.get('participant'))
                if not isinstance(person,dict):
                    same=[p for p in doc.get('collaborationParticipants',[]) if isinstance(p,dict) and p.get('engine')==engine]
                    if len(same)==1:person=same[0]
                if isinstance(person,dict):model=model_id(person.get('model'))
            group=get(engine,model);group['used']=True
            values=counters(snapshot.get('total'));add(group['tokenUsage']['total'],values)
            group['totalsReported']|=bool(values)
            group['totalsPartial']|='totalTokens' not in values or bool(row.get('counterRegression')) or bool(row.get('totalsPartial')) or snapshot.get('model') is None
            context=context_snapshot(source,row,snapshot)
            # A single native source can run several models; only its current model is active.
            if model_id(snapshot.get('model'))!=model_id(row.get('contextModel',row.get('model'))):context['active']=False;context['historical']=True
            group['contexts'].append(context);group['isActive']|=context['active']
        if row.get('mode')=='collaboration':
            group=get(engine,row.get('model'));stats=row.get('stats')
            if not isinstance(stats,dict):stats=execution_stats([],row.get('status'),row.get('startedAt'),row.get('finishedAt'));stats['partial']=True
            entries=stats.get('byModel') or [{'engine':engine,'model':row.get('model'),'stats':stats}]
            for entry in entries:
                target=get(entry.get('engine') or engine,entry.get('model'));target['used']=True
                merge_stats(target['stats'],entry['stats']);merge_stats(all_stats,entry['stats'])
    for group in groups.values():
        contexts=group['contexts']
        reported=[c for c in contexts if c['reported']]
        if reported:group['context']=copy.deepcopy(max(reported,key=lambda c:c['updatedAt'] or 0))
        elif contexts:group['context']=copy.deepcopy(max(contexts,key=lambda c:c['updatedAt'] or 0))
        group['contexts'].sort(key=lambda c:(c['updatedAt'] or 0,c['sourceId']))
        if group['used'] and not contexts:group['totalsPartial']=True
        group['historical']=group['used'] and not group['isCurrent'] and not group['isConfigured'] and not group['isActive']
        group['stats']['partial']|=not group['modelKnown']
        # Native internal models may report usage without exposing their turns.
        if group['used'] and not group['stats']['executions']:group['stats']['partial']=True
    total={};by_engine={}
    for group in groups.values():
        add(total,group['tokenUsage']['total']);add(by_engine.setdefault(group['engine'],{}),group['tokenUsage']['total'])
    turns=doc.get('turns',[])
    # Visible rounds are counted once; collaborator executions remain a separate metric.
    all_stats.update(turns=len(turns),userMessages=sum(i.get('type')=='userMessage' for t in turns for i in t.get('items',[])),
        models=sum(g['used'] for g in groups.values()),configuredModels=sum(g['isConfigured'] for g in groups.values()),
        historicalModels=sum(g['historical'] for g in groups.values()))
    all_stats['messages']=all_stats['userMessages']+all_stats['assistantMessages']
    all_stats['partial']|=any(g['stats']['partial'] for g in groups.values() if g['used'])
    return {'schemaVersion':2,'byModel':sorted(groups.values(),key=lambda g:(g['engine'],g['model'] or '')),
        'sessionStats':all_stats,'byEngine':by_engine,'total':total,'totalsPartial':any(g['totalsPartial'] for g in groups.values() if g['used'])}
