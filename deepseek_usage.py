"""Incremental, read-only accounting from DSH's durable native session log.

ACP exposes context occupancy only. DSH's v1-v4 JSONL records also preserve
provider usage, including cache buckets and failed/retried model attempts.
"""
import copy
import ctypes
import ctypes.util
import json
from pathlib import Path
import threading

from conversation_usage import add, counters, model_id


def normalize(raw):
    """DSH inputTokens excludes cache traffic; Ai-XS inputTokens includes it."""
    if not isinstance(raw,dict):return {}
    supplied_total='totalTokens' in raw;raw=counters(raw);out={}
    if 'inputTokens' in raw:out['inputTokens']=raw['inputTokens']+raw.get('cacheReadTokens',0)+raw.get('cacheWriteTokens',0)
    for source,target in (('outputTokens','outputTokens'),('cacheReadTokens','cachedInputTokens'),
        ('cacheWriteTokens','cacheWriteInputTokens'),('reasoningTokens','reasoningOutputTokens')):
        if source in raw:out[target]=raw[source]
    if supplied_total and 'totalTokens' in raw:out['totalTokens']=raw['totalTokens']
    return counters(out)


class Buffer(ctypes.Structure):
    _fields_=[('data',ctypes.c_void_p),('size',ctypes.c_size_t),('pos',ctypes.c_size_t)]


class Zstd:
    def __init__(self):
        self.lib=ctypes.CDLL(ctypes.util.find_library('zstd') or 'libzstd.so.1')
        self.lib.ZSTD_createDStream.restype=ctypes.c_void_p
        self.lib.ZSTD_initDStream.argtypes=[ctypes.c_void_p];self.lib.ZSTD_initDStream.restype=ctypes.c_size_t
        self.lib.ZSTD_decompressStream.argtypes=[ctypes.c_void_p,ctypes.POINTER(Buffer),ctypes.POINTER(Buffer)]
        self.lib.ZSTD_decompressStream.restype=ctypes.c_size_t
        self.lib.ZSTD_isError.argtypes=[ctypes.c_size_t];self.lib.ZSTD_isError.restype=ctypes.c_uint
        self.lib.ZSTD_freeDStream.argtypes=[ctypes.c_void_p]
        self.state=self.lib.ZSTD_createDStream()
        if not self.state:raise RuntimeError('无法创建 DSH 历史解压器')
        self.lib.ZSTD_initDStream(self.state)

    def decode(self, raw):
        source=ctypes.create_string_buffer(raw);incoming=Buffer(ctypes.cast(source,ctypes.c_void_p),len(raw),0)
        while True:
            target=ctypes.create_string_buffer(65536);outgoing=Buffer(ctypes.cast(target,ctypes.c_void_p),len(target),0)
            before=incoming.pos;code=self.lib.ZSTD_decompressStream(self.state,ctypes.byref(outgoing),ctypes.byref(incoming))
            if self.lib.ZSTD_isError(code):raise ValueError('DSH 会话压缩记录损坏')
            if outgoing.pos:yield target.raw[:outgoing.pos]
            if incoming.pos==incoming.size and outgoing.pos<outgoing.size:break
            if incoming.pos==before and not outgoing.pos:break

    def close(self):
        if self.state:self.lib.ZSTD_freeDStream(self.state);self.state=None

    def __del__(self):
        if getattr(self,'state',None):self.close()


class SessionUsage:
    def __init__(self, path, session):
        self.path=Path(path);self.session=session;self.lock=threading.RLock();self._reset()

    def _reset(self):
        decoder=getattr(self,'decoder',None)
        if decoder:decoder.close()
        self.decoder=Zstd() if self.path.suffix=='.zstd' else None
        self.offset=0;self.pending=b'';self.identity=None;self.header=False;self.partial=False
        self.modified=None
        self.model=None;self.window=None;self.models={};self.last=None;self.recorded=None
        self.last_slot=None;self.last_sample={};self.last_model=None;self.stale=False

    def _row(self, row):
        if not isinstance(row,dict):self.partial=True;return
        kind=row.get('type');data=row.get('data') or {}
        if kind=='session':
            if row.get('id')!=self.session or row.get('version') not in (1,2,3,4):raise ValueError('DSH 会话标识或格式不匹配')
            self.header=True;return
        if not self.header:raise ValueError('DSH 会话缺少文件头')
        if not isinstance(data,dict):self.partial=True;return
        if kind=='request/context':
            self.model=model_id(data.get('model'));self.window=data.get('contextWindow')
        elif kind=='llm/retry-started':
            if self.last_slot==(data.get('turn'),data.get('step')):self.last_slot=None
        elif kind in ('compaction/end','compaction/summary','compaction/prune'):
            self.stale=True
        elif kind in ('assistant/message','assistant/attempt'):
            raw=data.get('usage')
            if not isinstance(raw,dict):
                # Compact DSH streams wrap full usage chunks in `chunk` rows.
                stream=data.get('stream')
                if isinstance(stream,list):
                    for entry in reversed(stream):
                        if not isinstance(entry,dict):continue
                        chunk=entry.get('chunk',entry)
                        if isinstance(chunk,dict) and chunk.get('type')=='usage':raw=chunk.get('usage');break
            usage=normalize(raw)
            if not usage:return
            slot=(data.get('turn'),data.get('step'))
            # Match DSH's own fold: settlement replaces the attempt sample at
            # the same turn/step; retry-started opens a new billable attempt.
            previous=self.last_sample if self.last_slot==slot and self.last_model==self.model else {}
            group=self.models.setdefault(self.model or '',{'model':self.model,'total':{}})
            for key,value in usage.items():group['total'][key]=max(0,group['total'].get(key,0)-previous.get(key,0)+value)
            pressure=usage.get('inputTokens')
            if pressure is not None:
                group['last']={'inputTokens':pressure,'totalTokens':pressure}
                group['modelContextWindow']=self.window;group['contextStale']=False
            self.recorded=row.get('time');group['recordedAt']=self.recorded
            group['updatedAt']=self.recorded/1000 if isinstance(self.recorded,(int,float)) else 0
            self.last_slot=slot;self.last_sample=usage;self.last_model=self.model;self.last=usage;self.stale=False

    def read(self):
        with self.lock:
            stat=self.path.stat();identity=(stat.st_dev,stat.st_ino)
            if self.identity!=identity or stat.st_size<self.offset or (
                self.modified is not None and stat.st_size==self.offset and stat.st_mtime_ns!=self.modified):self._reset()
            self.identity=identity;self.modified=stat.st_mtime_ns
            # Bound work per refresh on large sessions; later reads continue
            # from the same stream rather than rescanning the history.
            budget=8*1024*1024;loaded=0
            with self.path.open('rb') as source:
                source.seek(self.offset)
                while loaded<budget:
                    raw=source.read(min(65536,budget-loaded))
                    if not raw:break
                    self.offset+=len(raw);loaded+=len(raw)
                    for block in self.decoder.decode(raw) if self.decoder else [raw]:
                        self.pending+=block
                        while b'\n' in self.pending:
                            line,self.pending=self.pending.split(b'\n',1)
                            try:self._row(json.loads(line))
                            except (UnicodeError,json.JSONDecodeError):self.partial=True
                        if len(self.pending)>16*1024*1024:raise ValueError('DSH 单条历史记录过大，未完成用量读取')
            total={}
            for group in self.models.values():add(total,group['total'])
            active=self.models.get(self.last_model or '',{})
            entries=copy.deepcopy(list(self.models.values()))
            for entry in entries:
                if entry['model']==self.last_model:entry['contextStale']=self.stale
            return {'tokenUsage':{'total':total,'last':copy.deepcopy(active.get('last',{})),
                'modelContextWindow':active.get('modelContextWindow',self.window),'byModel':entries},
                'model':self.last_model,'recordedAt':self.recorded,'contextStale':self.stale,
                'totalsPartial':self.partial or self.offset<stat.st_size}
