"""Durable user-facing worker transcripts, separate from rotating diagnostic logs."""
import copy
import json
from pathlib import Path
import threading
import time
from provider_manager import private_write
from realtime_events import apply as apply_live_event, settle as settle_live_items

MAX_TEXT=131072
MAX_ITEMS=1000
MAX_BYTES=4*1024*1024


def bounded(value):
    if isinstance(value,str):return value[:MAX_TEXT]+('\n[内容过长，已截断显示]' if len(value)>MAX_TEXT else '')
    if isinstance(value,list):return [bounded(v) for v in value[:1000]]
    if isinstance(value,dict):return {k:bounded(v) for k,v in value.items()}
    return value


class AttemptTranscript:
    def __init__(self, path=None):
        self.path=Path(path) if path else None;self.lock=threading.RLock();self.last_flush=0;self.dirty=False
        self.value={'version':1,'items':[],'status':'starting','omittedItems':0,'revision':0}
        if self.path and self.path.exists():
            try:
                value=json.loads(self.path.read_text())
                if not isinstance(value.get('items'),list) or any(not isinstance(i,dict) or not isinstance(i.get('id'),str) for i in value['items']):raise ValueError('Invalid transcript')
                self.value=value
            except (OSError,ValueError,AttributeError):self.value['historyError']='执行过程文件损坏或无法读取，仅显示可恢复的结果'

    def _item(self, ident, kind, at):
        found=next((i for i in self.value['items'] if i['id']==ident),None)
        if found is None:
            found={'id':ident,'type':kind,'startedAt':at,'status':'inProgress'};self.value['items'].append(found)
        return found

    def _merge(self, raw, at, completed=False):
        if not isinstance(raw,dict) or raw.get('type')=='userMessage':return
        ident=str(raw.get('id') or '')
        if not ident:return
        item=self._item(ident,raw.get('type','unknown'),at)
        fields=bounded(copy.deepcopy(raw));fields.pop('startedAt',None)
        for key in ('aggregatedOutput','text','content','summary'):
            if fields.get(key,'') is None and item.get(key) is not None:fields.pop(key,None)
        fields['id']=ident;item.update(fields)
        if completed:item['status']=raw.get('status') if raw.get('status') not in (None,'inProgress','running','pending','in_progress') else 'completed'

    def apply(self, event, flush=True):
        method=event.get('method','');p=event.get('params',{});at=event.get('receivedAt') or time.time()
        with self.lock:
            sequence=event.get('attemptSequence',0)
            if sequence and sequence<=self.value.get('revision',0):return
            self.value['revision']=sequence or self.value.get('revision',0)+1
            self.dirty=True
            if method in ('turn/started','turn/completed'):
                turn=p.get('turn',{});self.value['status']=turn.get('status','inProgress')
                for item in turn.get('items',[]):self._merge(item,at)
                if turn.get('error'):self.value['error']=bounded(turn['error'])
            elif method in ('item/started','item/completed'):
                self._merge(p.get('item'),at,method=='item/completed')
            elif method=='native/turnStatus':self.value['progress']=bounded(copy.deepcopy(p.get('progress',{})))
            elif method=='native/childTurn':
                self.value.setdefault('nativeChildren',{})[p.get('sourceThreadId','')+':'+str(p.get('sourceTurnId',''))]=bounded({
                    'sourceThreadId':p.get('sourceThreadId'),'sourceTurnId':p.get('sourceTurnId'),'engine':p.get('sourceEngine'), 'model':p.get('sourceModel'),
                    'turn':copy.deepcopy(p.get('childTurn') or {}),'stats':copy.deepcopy(p.get('stats') or {})})
            else:
                filtered=copy.deepcopy(p)
                if not apply_live_event(self.value,method,filtered):
                    if flush:self.flush()
                    return
                for item in self.value['items']:item.setdefault('startedAt',at)
            if method=='turn/completed':settle_live_items(self.value,self.value['status'])
            self.dirty=True
            if flush:self.flush(force=method in ('turn/completed','item/completed','native/childTurn'))

    def finish(self, outcome):
        with self.lock:
            self.value['revision']=self.value.get('revision',0)+1
            turn=outcome.get('turn',{})
            for item in turn.get('items',[]):self._merge(item,time.time())
            self.value['status']=outcome.get('status','failed')
            settle_live_items(self.value,self.value['status'])
            if outcome.get('error'):self.value['error']=bounded(outcome['error'])
            self.dirty=True;self.flush(force=True)

    def snapshot(self):
        with self.lock:return copy.deepcopy(self.value)

    def flush(self, force=False):
        with self.lock:
            if not self.path or not self.dirty or not force and time.monotonic()-self.last_flush<.4:return
            while len(self.value['items'])>MAX_ITEMS or len(json.dumps(self.value,ensure_ascii=False).encode())>MAX_BYTES:
                if not self.value['items']:break
                self.value['items'].pop(0);self.value['omittedItems']+=1
            private_write(self.path,self.value);self.last_flush=time.monotonic();self.dirty=False


def conversation(doc, folder, live, params):
    """Stable item IDs and a chronological cursor keep simultaneous streams separate."""
    entries=[];partial=False;revisions={};omitted=0
    for message in doc.get('messages',[]):
        entries.append({'id':'user:'+message['id'],'kind':'user','at':message['at'],'text':message['text'],'input':copy.deepcopy(message.get('input',[])),
                        'participantId':message['participantId'],'taskId':message['taskId']})
    people={p['id']:p for p in doc['participants']}
    for task in doc['tasks']:
        for index,attempt in enumerate(task.get('attempts',[])):
            config=attempt.get('config') or people.get(task['participantId'],{})
            path=Path(folder)/'attempts'/attempt['id']/'transcript.json'
            transcript=live.get(attempt['id'])
            legacy=False
            if transcript is None:
                transcript=AttemptTranscript(path)
                if not path.exists():
                    legacy=True
                    for number,event in enumerate(attempt.get('events',[])):
                        transcript.apply(dict(event,receivedAt=event.get('receivedAt') or attempt['startedAt']+number*.000001),flush=False)
            snap=transcript.snapshot();items=snap['items'];revisions[attempt['id']]=snap.get('revision',0)
            omitted+=snap.get('omittedItems',0)
            if attempt.get('result') and (not any(i.get('type') in ('agentMessage','assistantMessage') for i in items) or legacy and not any(i.get('type') in ('agentMessage','assistantMessage') and i.get('status')=='completed' for i in items)):
                items.append({'id':'result','type':'agentMessage','text':attempt['result'],'startedAt':attempt.get('finishedAt',attempt['startedAt']),'status':'completed'})
            source={'attemptId':attempt['id'],'taskId':task['id'],'taskTitle':task['title'],'attemptNumber':index+1,
                    'participantId':config.get('id',task['participantId']),'participant':config.get('name',''),
                    'engine':config.get('engine',''),'model':config.get('model',''),'attemptStatus':attempt['status']}
            for item in items:
                entries.append(dict(source,id=attempt['id']+':'+item['id'],kind='item',at=item.get('startedAt',attempt['startedAt']),item=item))
            partial=partial or bool(snap.get('omittedItems')) or bool(snap.get('historyError')) or legacy
            if snap.get('historyError'):
                entries.append(dict(source,id=attempt['id']+':history-error',kind='error',at=attempt['startedAt'],text=snap['historyError']))
            if attempt.get('error'):
                entries.append(dict(source,id=attempt['id']+':error',kind='error',at=attempt.get('finishedAt',attempt['startedAt']),text=attempt['error']))
    participant=params.get('participantId');taskid=params.get('taskId')
    entries=[e for e in entries if (not participant or e.get('participantId')==participant) and (not taskid or e.get('taskId')==taskid)]
    entries.sort(key=lambda e:(e['at'],e['id']))
    before=params.get('before')
    if before:
        if not isinstance(before,list) or len(before)!=2:raise ValueError('历史游标无效')
        entries=[e for e in entries if (e['at'],e['id'])<(float(before[0]),str(before[1]))]
    limit=max(1,min(100,int(params.get('limit',60))));more=len(entries)>limit;entries=entries[-limit:]
    cursor=[entries[0]['at'],entries[0]['id']] if entries else None
    return {'entries':entries,'hasMore':more,'before':cursor,'historyPartial':partial,'revisions':revisions,'omittedItems':omitted}
