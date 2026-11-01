"""One private Agent execution process; never shares mutable CLI homes with a peer."""
import json
import os
from pathlib import Path
import signal
import sys
import threading
import time


def main():
    folder=Path(sys.argv[1]).resolve();spec=json.loads((folder/'spec.json').read_text())
    home=folder/'home';home.mkdir(exist_ok=True)
    for name in ('.codex','.claude','.dsh'):(home/name).mkdir(exist_ok=True,mode=0o700)
    os.environ.update(HOME=str(home),CODEX_HOME=str(home/'.codex'),CLAUDE_CONFIG_DIR=str(home/'.claude'),DSH_HOME=str(home/'.dsh'),
        AI_XS_ENGINE_HOME=str(home),AI_XS_STATE_DIR=str(folder),AI_XS_SINGLE_AGENT='0',AI_XS_STRICT_MODELS='1')
    import agent_core as core
    from permission_modes import apply
    core.APP=core.AppServer();core.APP.diagnostics=True;core.PROVIDERS=core.ProviderManager(folder,home,home)
    core.CLAUDE=core.ClaudeBridge(core.APP,folder/'claude');core.DEEPSEEK=core.DeepSeekBridge(core.APP,folder/'deepseek')
    core.MCP=core.McpManager(core.APP,folder)
    write_lock=threading.Lock();done=threading.Event();state={};stopping=threading.Event()
    def write(value):
        with write_lock:print(json.dumps(value,ensure_ascii=False),flush=True)
    def identities():
        from worker_pool import process_identity
        previous=[]
        while not done.wait(.1):
            processes=[core.APP.process]
            for bridge in (core.CLAUDE,core.DEEPSEEK):
                with bridge.lock:processes.extend(run.get('process') for run in bridge.runs.values())
            children=[{'pid':p.pid,'identity':process_identity(p.pid)} for p in processes if p and p.poll() is None]
            if children!=previous:write({'children':children});previous=children
    threading.Thread(target=identities,daemon=True).start()
    original=core.APP._emit
    from collaboration_transcript import AttemptTranscript
    transcript=AttemptTranscript()
    def emit(event):
        original(event);transcript.apply(event,flush=False);write({'event':event})
        if event.get('method')=='turn/started':state['activeTurnId']=event.get('params',{}).get('turn',{}).get('id')
        if event.get('method')=='turn/completed':
            state['turn']=dict(event.get('params',{}).get('turn',{}),items=transcript.snapshot()['items']);done.set()
    core.APP._emit=emit;core.APP.local_tools.emit=emit
    class Extensions:
        def claude_plugins(self):return spec.get('plugins',[])
    for bridge in (core.APP,core.CLAUDE,core.DEEPSEEK):bridge.providers=core.PROVIDERS;bridge.mcp=core.MCP
    core.CLAUDE.extensions=Extensions()
    def cleanup(*unused):
        stopping.set();done.set()
    signal.signal(signal.SIGTERM,cleanup);signal.signal(signal.SIGINT,cleanup)
    def guide(cmd):
        try:
            if stopping.is_set() or done.is_set() or not state.get('activeTurnId'):raise ValueError('成员尚未就绪或已经结束，消息未引入')
            p=dict(cmd['params'],threadId=state['threadId'],expectedTurnId=state['activeTurnId'])
            response=bridge.call('turn/steer',p)
            write({'controlResult':{'requestId':cmd['requestId'],'ok':True,'result':response}})
        except Exception as error:write({'controlResult':{'requestId':cmd['requestId'],'ok':False,'unknown':isinstance(error,TimeoutError),'error':str(error)}})
    def controls():
        for raw in sys.stdin:
            try:
                cmd=json.loads(raw)
                if cmd['method']=='stop':cleanup()
                elif cmd['method']=='turn/steer':
                    threading.Thread(target=guide,args=(cmd,),name='worker-guidance',daemon=True).start()
                elif cmd['method']=='approvals/respond':
                    p=cmd['params'];engine=spec['config']['engine']
                    if engine=='codex':core.APP.respond(p['id'],p['result'])
                    else:{'claude':core.CLAUDE,'deepseek':core.DEEPSEEK}[engine].respond(p['id'],p['result'])
            except Exception as error:write({'controlError':str(error)})
        cleanup()
    threading.Thread(target=controls,daemon=True).start()
    result={};thread=None;engine=None;bridge=None
    try:
        if stopping.is_set():raise InterruptedError('启动前已停止')
        config=spec['config'];engine=config['engine'];bridge={'codex':core.APP,'claude':core.CLAUDE,'deepseek':core.DEEPSEEK}[engine]
        if engine=='codex':core.APP.start()
        args=apply({'cwd':spec['cwd'],'model':config['model'],'developerInstructions':spec.get('instructions','')},config['permission'],'thread/start',engine)
        if engine=='codex':args['modelProvider']='ai_xs'
        thread=bridge.call('thread/start',args)['thread'];state['threadId']=thread['id'];write({'threadId':thread['id']})
        params=apply({'threadId':thread['id'],'input':[{'type':'text','text':spec['prompt']}]+spec.get('attachments',[]),
            'model':config['model'],'effort':config.get('effort') or None,'strictModel':True},config['permission'],'turn/start',engine)
        if stopping.is_set():raise InterruptedError('提交前已停止')
        # DeepSeek may hold the RPC until completion. Keep the timeout/control loop independent.
        submission={}
        def submit():
            try:bridge.call('turn/start',params)
            except Exception as error:submission['error']=error;done.set()
        threading.Thread(target=submit,name='worker-submit',daemon=True).start()
        deadline=time.monotonic()+spec.get('timeout',3600)
        while not done.wait(.25):
            if time.monotonic()>deadline:raise TimeoutError('子任务超时，已停止；请检查成果后手动继续')
        turn=state.get('turn',{})
        if submission.get('error'):raise submission['error']
        if stopping.is_set():result={'status':'interrupted','error':'执行已中断，已完成内容保留'}
        elif turn.get('status')!='completed':result={'status':'failed','error':str(turn.get('error') or 'Agent 未正常完成')}
        else:result={'status':'succeeded'}
        result['turn']=turn
    except Exception as error:result={'status':'interrupted' if stopping.is_set() else 'failed','error':str(error)}
    finally:
        # Recover the latest persisted report even after failure or interruption.
        # Reading usage never submits another model request.
        if thread is not None:
            try:
                if engine=='codex':
                    native=bridge.call('thread/read',{'threadId':thread['id'],'includeTurns':False},timeout=3)['thread']
                    usage=core.read_thread_usage(thread['id'],native)
                    if usage.get('tokenUsage') is not None:usage['tokenUsage']=core.APP.codex_children.merge_usage(thread['id'],usage['tokenUsage'])
                else:usage=bridge.call('usage/read',{'threadId':thread['id']})
                if usage.get('tokenUsage') is not None:result['usage']=usage
            except Exception:pass
        # Each bridge owns a process group; close and wait before reporting final exit.
        core.CLAUDE.close();core.DEEPSEEK.close();core.APP.local_tools.close();core.APP.stop()
    write({'finished':result})


if __name__=='__main__':main()
