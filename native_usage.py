"""Incremental local usage recovery; never launches a CLI or guesses a model."""
import copy
import json
import threading
from pathlib import Path
from conversation_usage import add, counters, model_id


def claude_tokens(raw):
    if not isinstance(raw, dict):return {}
    values={k:v for k,v in raw.items() if k in ('input_tokens','output_tokens','cache_read_input_tokens','cache_creation_input_tokens')
            and isinstance(v,(int,float)) and not isinstance(v,bool) and v>=0}
    result={}
    if 'input_tokens' in values:
        result['inputTokens']=sum(values.get(k,0) for k in ('input_tokens','cache_read_input_tokens','cache_creation_input_tokens'))
    for key,target in (('output_tokens','outputTokens'),('cache_read_input_tokens','cachedInputTokens'),('cache_creation_input_tokens','cacheWriteInputTokens')):
        if key in values:result[target]=values[key]
    return counters(result)


class UsageFile:
    def __init__(self, path, engine):
        self.path=Path(path);self.engine=engine;self.lock=threading.RLock();self.reset()

    def reset(self):
        self.offset=0;self.pending=b'';self.identity=None;self.anchor=b'';self.models={};self.previous={};self.seen={}
        self.model=None;self.last_model=None;self.last={};self.window=None;self.recorded=None;self.partial=False;self.stale=False;self.compacted=None

    def slot(self, model):
        return self.models.setdefault(model or '',{'model':model_id(model),'total':{}})

    def consume(self, row):
        kind=row.get('type');stamp=row.get('timestamp')
        if self.engine=='codex':
            payload=row.get('payload') or {}
            if not isinstance(payload,dict):return
            if kind=='turn_context':
                self.model=model_id(payload.get('model'));return
            if kind=='compacted':
                self.stale=True;self.compacted=stamp
                if self.last_model in self.models:self.models[self.last_model]['contextStale']=True
                return
            if kind!='event_msg' or payload.get('type')!='token_count':return
            info=payload.get('info')
            if not isinstance(info,dict):return
            names={'input_tokens':'inputTokens','cached_input_tokens':'cachedInputTokens','cache_write_input_tokens':'cacheWriteInputTokens',
                   'output_tokens':'outputTokens','reasoning_output_tokens':'reasoningOutputTokens','total_tokens':'totalTokens'}
            def normalized(v):
                return counters({target:v[key] for key,target in names.items() if key in v}) if isinstance(v,dict) else {}
            total=normalized(info.get('total_token_usage'));last=normalized(info.get('last_token_usage'))
            if not total and not last:return
            delta={}
            for key,amount in total.items():
                before=self.previous.get(key,0)
                if amount<before:self.partial=True
                delta[key]=max(0,amount-before)
                self.previous[key]=max(before,amount)
            # A fork's first cumulative sample can contain an unidentifiable
            # inherited bill. Only its last request is attributable to this model.
            if not self.models and delta and last:
                inherited={k:max(0,v-last.get(k,0)) for k,v in delta.items()}
                if any(inherited.values()):
                    add(self.slot(None)['total'],inherited)
                    delta={k:v-inherited.get(k,0) for k,v in delta.items()}
            slot=self.slot(self.model);add(slot['total'],delta)
            window=info.get('model_context_window')
            if last:
                self.last=last;self.last_model=self.model or '';self.recorded=stamp;self.stale=False
                self.window=window if isinstance(window,int) and not isinstance(window,bool) and window>0 else None
                slot.update(last=last,modelContextWindow=self.window,recordedAt=stamp,contextStale=False)
            if not total:self.partial=True
        else:
            if row.get('isSidechain'):return
            if kind=='system' and row.get('subtype')=='compact_boundary':
                self.stale=True
                if self.last_model in self.models:self.models[self.last_model]['contextStale']=True
                return
            if kind!='assistant':return
            msg=row.get('message')
            if not isinstance(msg,dict):return
            tokens=claude_tokens(msg.get('usage'))
            if not tokens:return
            model=model_id(msg.get('model'));key=msg.get('id') or row.get('uuid')
            if not key:self.partial=True;return
            before=self.seen.get(key,{})
            high={k:max(v,before.get(k,0)) for k,v in {**before,**tokens}.items()}
            self.seen[key]=high
            add(self.slot(model)['total'],{k:v-before.get(k,0) for k,v in high.items()})
            self.last=high;self.last_model=model or '';self.model=model;self.recorded=stamp;self.stale=False
            self.slot(model).update(last=high,recordedAt=stamp,contextStale=False)

    def read(self):
        with self.lock:
            stat=self.path.stat();identity=(stat.st_dev,stat.st_ino)
            if self.identity!=identity or stat.st_size<self.offset or stat.st_size==self.offset and getattr(self,'mtime',None)!=stat.st_mtime_ns:
                self.reset();self.identity=identity
            with self.path.open('rb') as stream:
                if self.offset:
                    stream.seek(max(0,self.offset-len(self.anchor)))
                    if stream.read(len(self.anchor))!=self.anchor:
                        # A log may be rewritten and grow between two reads;
                        # inode and length alone cannot identify that case.
                        self.reset();self.identity=identity
                stream.seek(self.offset)
                while True:
                    block=stream.read(65536)
                    if not block:break
                    self.offset+=len(block);lines=(self.pending+block).split(b'\n');self.pending=lines.pop()
                    for line in lines:
                        try:row=json.loads(line)
                        except (ValueError,UnicodeError):continue
                        if isinstance(row,dict):self.consume(row)
                    if len(self.pending)>16*1024*1024:self.pending=b'';self.partial=True
                stream.seek(max(0,self.offset-128));self.anchor=stream.read(min(128,self.offset))
            # Claude histories can end with a complete JSON record without LF.
            # Keep it pending so an incomplete final record is retried on append.
            if self.pending:
                try:row=json.loads(self.pending)
                except (ValueError,UnicodeError):row=None
                if isinstance(row,dict):self.consume(row)
            self.mtime=stat.st_mtime_ns
            total={}
            for slot in self.models.values():add(total,slot['total'])
            return {'model':model_id(self.last_model),'tokenUsage':{'total':total,'last':copy.deepcopy(self.last),
                    'modelContextWindow':self.window,'byModel':copy.deepcopy(list(self.models.values()))},
                    'recordedAt':self.recorded,'contextStale':self.stale,'totalsPartial':self.partial,
                    'nativeUsageRecovered':True,'compactedAt':self.compacted}


_cache={};_lock=threading.RLock()


def read_usage(path, engine):
    key=(str(path),engine)
    with _lock:
        reader=_cache.get(key)
        if reader is None:
            reader=_cache[key]=UsageFile(path,engine)
            while len(_cache)>12:_cache.pop(next(iter(_cache)))
    return reader.read()
