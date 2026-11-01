"""Route explicitly related native child threads into their owning visible turn."""
import copy
import threading
import json
import hashlib
from pathlib import Path
from realtime_events import apply as apply_live, settle
from conversation_usage import execution_stats, add, record


class CodexChildren:
    def __init__(self, watch=None, folder=None):
        self.parents={};self.models={};self.parent_tools={};self.current={};self.turns={}
        self.watched=set();self.seen=set();self.watch=watch;self.lock=threading.RLock()
        self.usage={}
        self.folder=Path(folder) if folder else None;self.loaded=set()
        self.pending_roots={}
        self.accounting={'engine':'codex','usageSnapshots':{}}

    def set_model(self,tid,model):
        if isinstance(tid,str) and isinstance(model,str) and model:
            with self.lock:self.models[tid]=model

    def _sample(self,tid,usage):
        if tid not in self.accounting['usageSnapshots'] and tid in self.usage:
            record(self.accounting,'codex',tid,{'tokenUsage':self.usage[tid],'model':self.models.get(tid)})
        record(self.accounting,'codex',tid,{'tokenUsage':usage,'model':self.models.get(tid)})
        self.usage[tid]=copy.deepcopy(usage)
        self.usage[tid]['byModel']=copy.deepcopy(list(self.accounting['usageSnapshots'][tid]['modelSnapshots'].values()))

    def active_children(self,root,turnid=None):
        with self.lock:return [(tid,turn) for (tid,turn),state in self.turns.items() if self.root(tid)==root and
            (not turnid or state.get('owner')==turnid) and state.get('status') in ('inProgress','pending')]

    def interrupt(self,root,turnid):
        with self.lock:
            pending=self.pending_roots.get((root,turnid))
            if pending:pending['params']['turn']['status']='interrupted'

    def _path(self,root):return self.folder/(hashlib.sha256(root.encode()).hexdigest()+'.json')

    def _restore(self,root):
        if not self.folder or not isinstance(root,str) or root in self.loaded:return
        self.loaded.add(root)
        try:values=json.loads(self._path(root).read_text())
        except (OSError,ValueError):return
        for row in values if isinstance(values,list) else []:
            tid=row.get('id') if isinstance(row,dict) else None
            if not isinstance(tid,str) or tid==root:continue
            self.parents.setdefault(tid,root)
            if row.get('model'):self.models.setdefault(tid,row['model'])
            if isinstance(row.get('usage'),dict):self.usage.setdefault(tid,row['usage'])

    def _persist(self,root):
        if not self.folder or not root:return
        from provider_manager import private_write
        try:
            self.folder.mkdir(parents=True,exist_ok=True)
            private_write(self._path(root),[{'id':tid,'model':self.models.get(tid),'usage':usage}
                for tid,usage in self.usage.items() if tid!=root and self.root(tid)==root])
        except OSError as error:
            import logging
            logging.warning('Codex 子任务用量保存失败：%s',error)

    def forget(self,root):
        with self.lock:
            ids={tid for tid in self.parents if self.root(tid)==root}|{root}
            for mapping in (self.parents,self.models,self.parent_tools,self.current,self.usage):
                for tid in ids:mapping.pop(tid,None)
            for key in list(self.turns):
                if key[0] in ids:self.turns.pop(key,None)
            for key in list(self.pending_roots):
                if key[0] in ids:self.pending_roots.pop(key,None)
            self.watched.difference_update(ids);self.seen.difference_update(ids);self.loaded.discard(root)
            for tid in ids:self.accounting['usageSnapshots'].pop(tid,None)
            if self.folder:self._path(root).unlink(missing_ok=True)

    def merge_usage(self, root, usage):
        with self.lock:
            self._restore(root)
            if isinstance(usage,dict):self._sample(root,usage)
            own=copy.deepcopy(self.usage.get(root) or {});groups={}
            for tid,sample in self.usage.items():
                if self.root(tid)!=root:continue
                entries=sample.get('byModel')
                if not entries:entries=[{'model':self.models.get(tid),'total':sample.get('total') or {},
                    'last':sample.get('last') or {},'modelContextWindow':sample.get('modelContextWindow')}]
                for entry in entries:
                    name=entry.get('model');slot=groups.setdefault(name or '',{'model':name,'total':{}})
                    add(slot['total'],entry.get('total'))
                    if tid==root or 'last' not in slot:
                        for key in ('last','modelContextWindow','contextStale','recordedAt','updatedAt'):
                            if key in entry:slot[key]=copy.deepcopy(entry[key])
            total={}
            for entry in groups.values():add(total,entry['total'])
            own.update(total=total,byModel=list(groups.values()))
            return own

    def bind(self, thread, model=None):
        if not isinstance(thread,dict) or not isinstance(thread.get('id'),str):return
        tid=thread['id'];parent=thread.get('parentThreadId')
        if isinstance(parent,str) and parent and parent!=tid:self.parents[tid]=parent
        if model or thread.get('model'):self.models[tid]=model or thread['model']

    def replay(self, thread, model=None):
        """A fast child may finish before its subscription is established."""
        with self.lock:
            self.bind(thread,model);output=[]
            if not isinstance(thread,dict):return output
            tid=thread.get('id')
            if self.root(tid)==tid:return output
            for turn in thread.get('turns') or []:
                state=self.turns.get((tid,turn.get('id')))
                if state and (state.get('items') or state.get('status') not in ('inProgress','pending')):continue
                output.extend(self._observe({'type':'notification',
                    'method':'turn/started' if turn.get('status') in ('inProgress','pending') else 'turn/completed',
                    'params':{'threadId':tid,'turn':turn}}))
            return output

    def root(self, tid):
        seen=set()
        while tid in self.parents:
            if tid in seen or len(seen)>=32:return None
            seen.add(tid);tid=self.parents[tid]
        return tid

    def discover(self, parent, item):
        if not isinstance(item,dict):return
        if item.get('type')=='collabAgentToolCall':
            children=item.get('receiverThreadIds') or []
            if item.get('tool') not in ('spawnAgent','spawn_agent'):
                children=[child for child in children if self.parents.get(child)==parent]
        elif item.get('type')=='subAgentActivity':children=[item.get('agentThreadId')]
        else:return
        for child in children:
            if not isinstance(child,str) or not child or child==parent:continue
            self.parents.setdefault(child,parent);self.parent_tools.setdefault(child,item.get('id',''))
            if item.get('model'):self.models[child]=item['model']
            if child not in self.watched:
                self.watched.add(child)
                if self.watch:self.watch(child)

    def metadata(self, tid, turnid):
        return {'nativeChild':True,'sourceEngine':'codex','sourceThreadId':tid,'sourceTurnId':turnid,
                'sourceModel':self.models.get(tid),'parentToolUseId':self.parent_tools.get(tid) or 'codex-child:'+tid}

    def item(self, tid, turnid, raw):
        value=copy.deepcopy(raw);value.update(self.metadata(tid,turnid))
        value['id']='child:'+tid+':'+str(turnid)+':'+str(raw.get('id','item'))
        if value.get('type') in ('agentMessage','assistantMessage'):value['type']='subagentMessage'
        return value

    def observe(self, event):
        with self.lock:return self._observe(event)

    def _observe(self, event):
        p=event.get('params') or {};method=event.get('method','')
        if method=='thread/started':self.bind(p.get('thread'))
        tid=p.get('threadId') or (p.get('thread') or {}).get('id')
        if not tid:return [event]
        turn=p.get('turn') or {};turnid=p.get('turnId') or turn.get('id') or self.current.get(tid)
        if method in ('turn/started','turn/completed') and turn.get('id'):self.current[tid]=turn['id']
        if method in ('item/started','item/completed'):self.discover(tid,p.get('item'))
        for raw in turn.get('items') or []:self.discover(tid,raw)
        root=self.root(tid)
        if method=='model/rerouted' and p.get('toModel'):self.set_model(tid,p['toModel'])
        if method=='thread/tokenUsage/updated' and isinstance(p.get('tokenUsage'),dict):
            self._restore(root)
            self._sample(tid,p['tokenUsage'])
            self._persist(root)
            if root==tid:
                event=copy.deepcopy(event);event['params']['tokenUsage']=self.merge_usage(root,None)
        if root==tid or root is None:
            if method=='turn/completed' and turn.get('status')=='completed' and self.active_children(tid,turnid):
                self.pending_roots[(tid,turnid)]=copy.deepcopy(event)
                return [{'type':'notification','method':'item/completed','params':{'threadId':tid,'turnId':turnid,'item':raw}}
                    for raw in turn.get('items') or [] if raw.get('type')!='userMessage']
            return [event]
        if method=='thread/started':return []
        key=(tid,turnid);state=self.turns.get(key)
        if state is None:
            owner=turn.get('rootTurnId') or self.current.get(root)
            if not owner:return []
            # Thread-scoped warnings can precede the first turn. They must not
            # create an active phantom turn which can never receive completion.
            state={'id':turnid,'owner':owner,'items':[],'status':'inProgress' if turnid else 'unknown'};self.turns[key]=state
        owner=state['owner'];self.seen.add(tid)
        base={'threadId':root,'turnId':owner,**self.metadata(tid,turnid)}
        def notification(name,params):return {'type':event.get('type','notification'),'method':name,'params':dict(base,**params)}
        if method in ('turn/started','turn/completed'):
            state.update({k:copy.deepcopy(v) for k,v in turn.items() if k not in ('items','owner')})
            output=[]
            for raw in turn.get('items') or []:
                if raw.get('type')=='userMessage':continue
                apply_live(state,'item/started',{'item':raw})
            if method=='turn/completed':settle(state,turn.get('status','completed'))
            for raw in state['items']:
                output.append(notification('item/started' if method=='turn/started' else 'item/completed',{'item':self.item(tid,turnid,raw)}))
            status=turn.get('status','inProgress')
            error=turn.get('error');error=error.get('message','') if isinstance(error,dict) else str(error or '')
            task={'id':'child-task:'+tid+':'+str(turnid),'type':'taskProgress','tool':'Codex 子任务',
                  'text':error,'status':status,**self.metadata(tid,turnid)}
            output.append(notification('item/started' if method=='turn/started' else 'item/completed',{'item':task}))
            if method=='turn/completed':
                output.append(notification('native/childTurn',{'childTurn':copy.deepcopy(state),
                    'stats':execution_stats(state['items'],status,turn.get('startedAt'),turn.get('completedAt'),turn.get('durationMs'))}))
                # Keep live turns and a bounded tail for late deltas. Durable
                # transcripts and token snapshots do not depend on this cache.
                completed=[k for k,v in self.turns.items() if v.get('status') not in ('inProgress','pending')]
                for old in completed[:-128]:self.turns.pop(old,None)
                pending=self.pending_roots.get((root,owner))
                if pending and not self.active_children(root,owner):
                    output.append(self.pending_roots.pop((root,owner)))
            return output
        if method=='thread/tokenUsage/updated':
            return [notification('thread/tokenUsage/updated',{'tokenUsage':self.merge_usage(root,None),'model':self.models.get(root)})]
        value=copy.deepcopy(p);value.pop('threadId',None);value.pop('turnId',None)
        if method in ('item/started','item/completed'):
            raw=p.get('item') or {}
            if raw.get('type')=='userMessage':return []
            apply_live(state,method,{'item':raw});value['item']=self.item(tid,turnid,raw)
        elif method in ('hook/started','hook/completed','turn/plan/updated','turn/diff/updated','error','model/rerouted','warning','guardianWarning'):
            if method=='model/rerouted' and p.get('toModel'):self.models[tid]=p['toModel']
            before=copy.deepcopy(state.get('items',[]));apply_live(state,method,p)
            old={i['id']:i for i in before}
            return [notification('item/started' if raw.get('status') in ('inProgress','pending') else 'item/completed',
                {'item':self.item(tid,turnid,raw)}) for raw in state['items'] if raw!=old.get(raw['id'])]
        elif method.startswith('item/'):
            apply_live(state,method,p)
            if p.get('itemId'):value['itemId']=self.item(tid,turnid,{'id':p['itemId']})['id']
        if event.get('type')=='approval':
            projected=copy.deepcopy(event);projected['params']=dict(base,**value);return [projected]
        return [notification(method,value)]
