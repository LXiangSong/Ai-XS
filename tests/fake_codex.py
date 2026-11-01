"""Persistent app-server peer for cross-Agent private-pipe integration tests."""
import json
import os
from pathlib import Path
import sys
import uuid
import time

if '--version' in sys.argv:
    print('Codex protocol test');sys.exit()
folder=Path(os.environ['CODEX_HOME']);folder.mkdir(parents=True,exist_ok=True)
path=folder/'fake-threads.json'
threads=json.loads(path.read_text()) if path.exists() else {}
def emit(value):print(json.dumps({'jsonrpc':'2.0',**value}),flush=True)
def reply(message,result):emit({'id':message['id'],'result':result})
def notify(method,params):emit({'method':method,'params':params})
def save():path.write_text(json.dumps(threads))
for line in sys.stdin:
    message=json.loads(line);method=message.get('method');params=message.get('params',{})
    if os.environ.get('AI_XS_TEST_WIRE_LOG'):
        with Path(os.environ['AI_XS_TEST_WIRE_LOG']).open('a') as log:log.write(json.dumps({'engine':'codex','message':message})+'\n')
    if 'id' not in message:continue
    if method=='initialize':reply(message,{'userAgent':'Codex test'})
    elif method=='model/list':reply(message,{'data':[{'id':'test-model','model':'test-model','supportedReasoningEfforts':[{'reasoningEffort':e} for e in ('low','high','max')]}]})
    elif method in ('config/value/write','config/mcpServer/reload'):reply(message,{})
    elif method=='thread/start':
        tid=str(uuid.uuid4());threads[tid]={'id':tid,'cwd':params['cwd'],'model':params.get('model'),'turns':[]}
        save();reply(message,{'thread':threads[tid]})
    elif method in ('thread/resume','thread/read'):reply(message,{'thread':threads[params['threadId']]})
    elif method=='thread/delete':
        threads.pop(params['threadId'],None);save();reply(message,{})
        notify('thread/deleted',{'threadId':params['threadId']})
    elif method=='thread/list':reply(message,{'data':list(threads.values()),'nextCursor':None})
    elif method=='thread/turns/list':reply(message,{'data':list(reversed(threads[params['threadId']]['turns'])),'nextCursor':None})
    elif method=='turn/start':
        tid=params['threadId'];turn={'id':str(uuid.uuid4()),'status':'inProgress','items':[]}
        notify('turn/started',{'threadId':tid,'turn':turn})
        reply(message,{'turn':turn})
        if any('usage-accounting' in part.get('text','') for part in params['input']):
            prior=threads[tid].get('usageTotal',0);threads[tid]['usageTotal']=prior+60
            notify('thread/tokenUsage/updated',{'threadId':tid,'tokenUsage':{
                'total':{'inputTokens':threads[tid]['usageTotal'],'outputTokens':10},
                'last':{'totalTokens':45},'modelContextWindow':1000}})
        if any('wait-for-interrupt' in part.get('text','').split('\n原会话背景：')[0] for part in params['input']):
            threads[tid]['turns'].append(turn);save();continue
        tool={'id':'live-command','type':'commandExecution','command':'echo live','status':'inProgress','aggregatedOutput':''}
        notify('item/started',{'threadId':tid,'turnId':turn['id'],'item':tool})
        notify('item/commandExecution/outputDelta',{'threadId':tid,'turnId':turn['id'],'itemId':tool['id'],'delta':'live\n'})
        if any('progress-demo' in part.get('text','') for part in params['input']):time.sleep(.25)
        tool.update(status='completed',aggregatedOutput='live\n')
        notify('item/completed',{'threadId':tid,'turnId':turn['id'],'item':tool})
        turn['items'].append(tool)
        item={'id':'reply','type':'agentMessage','text':'Codex test reply'}
        notify('item/completed',{'threadId':tid,'turnId':turn['id'],'item':item})
        turn['items'].append(item);turn['status']='completed';threads[tid]['turns'].append(turn);save()
        notify('turn/completed',{'threadId':tid,'turn':turn})
    elif method=='turn/steer':
        tid=params['threadId'];turn=next((t for t in threads[tid]['turns'] if t['id']==params['expectedTurnId'] and t['status']=='inProgress'),None)
        if turn is None:
            emit({'id':message['id'],'error':{'code':-32602,'message':'active turn changed'}});continue
        item={'id':params.get('clientUserMessageId') or str(uuid.uuid4()),'type':'userMessage','clientId':params.get('clientUserMessageId'),'content':params['input']}
        turn['items'].append(item);save();notify('item/completed',{'threadId':tid,'turnId':turn['id'],'item':item});reply(message,{'turnId':turn['id']})
        if any('finish-after-guide' in part.get('text','') for part in params['input']):
            turn['status']='completed';save();notify('turn/completed',{'threadId':tid,'turn':turn})
    elif method=='turn/interrupt':
        turn=next(t for t in threads[params['threadId']]['turns'] if t['id']==params['turnId'])
        turn['status']='interrupted';save();reply(message,{})
        notify('turn/completed',{'threadId':params['threadId'],'turn':turn})
    else:emit({'id':message['id'],'error':{'code':-32601,'message':'Unsupported test method '+str(method)}})
