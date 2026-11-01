"""Exercise ACP request/notification correlation without a model API."""
import json
import os
import sys
import time
from pathlib import Path

if '--version' in sys.argv:
    print('DeepSeek Harness test');sys.exit()

def emit(value):print(json.dumps({'jsonrpc':'2.0',**value}),flush=True)
def reply(message,result):emit({'id':message['id'],'result':result})
def update(kind,**value):emit({'method':'session/update','params':{'sessionId':'native-deepseek','update':{'sessionUpdate':kind,**value}}})
options=[{'id':'model','currentValue':'test-model','options':[{'group':'test','options':[{'value':'test-model','name':'Test DeepSeek'}]}]},
    {'id':'reasoning_effort','currentValue':'high','options':[{'value':'low'},{'value':'high'}]}]
if os.environ.get('DEEPSEEK_TEST_ENCODED_MODELS')=='1':
    options[0]={'id':'model','currentValue':'["ai-web","first-model"]','options':[{'group':'provider','options':[
        {'value':'["ai-web","first-model"]','name':'First'},
        {'value':'["ai-web","second-model"]','name':'Second'}]}]}
if os.environ.get('AI_XS_TEST_MULTIMODEL')=='1':options[0]['options'][0]['options'].append({'value':'second-model','name':'Second model'})
pending=None;selected_model='test-model'
for line in sys.stdin:
    message=json.loads(line);method=message.get('method');params=message.get('params',{})
    if os.environ.get('AI_XS_TEST_WIRE_LOG'):
        with Path(os.environ['AI_XS_TEST_WIRE_LOG']).open('a') as log:log.write(json.dumps({'engine':'deepseek','message':message})+'\n')
    if method=='initialize':reply(message,{'protocolVersion':1,'agentCapabilities':{'promptCapabilities':{'image':False}}})
    elif method=='session/new':reply(message,{'sessionId':'native-deepseek','configOptions':options})
    elif method=='session/resume':reply(message,{'configOptions':options})
    elif method=='session/set_config_option':
        option=next(o for o in options if o['id']==params['configId'])
        choices=option['options']
        if choices and 'options' in choices[0]:choices=choices[0]['options']
        if not any(o['value']==params['value'] for o in choices):
            emit({'id':message['id'],'error':{'code':-32602,'message':'Unknown option value'}})
        else:
            option['currentValue']=params['value'];reply(message,{'configOptions':options})
            if params['configId']=='model':selected_model=params['value']
    elif method=='session/close':reply(message,{})
    elif method=='session/list':reply(message,{'sessions':[]})
    elif method=='session/prompt':
        text=' '.join(p.get('text','') for p in params['prompt'])
        if 'native-usage' in text:
            # Fixtures must never fall back to the user's real DSH session store.
            if not os.environ.get('DSH_HOME'):
                raise RuntimeError('native-usage fixtures require an isolated DSH_HOME')
            root=Path(os.environ['DSH_HOME'])/'sessions'/'fake-project'/'native-deepseek'
            root.mkdir(parents=True,exist_ok=True);path=root/'session.v4.jsonl'
            rows=[{'type':'session','version':4,'id':'native-deepseek'},
                {'type':'request/context','seq':1,'time':1000,'data':{'model':selected_model,'contextWindow':1000}},
                {'type':'assistant/message','seq':2,'time':2000,'data':{'turn':1,'step':1,'usage':{'inputTokens':10,'outputTokens':2,'cacheReadTokens':3}}}]
            path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
            update('agent_message_chunk',content={'type':'text','text':'Native usage available without ACP usage_update'})
            if 'wait' in text:pending=message
            else:reply(message,{'stopReason':'end_turn'})
        elif 'progress-demo' in text:
            update('agent_thought_chunk',content={'type':'text','text':'实时'})
            update('agent_thought_chunk',content={'type':'text','text':'思考'})
            update('tool_call',toolCallId='live-tool',title='read_file',rawInput={'path':'README.md'},status='in_progress')
            update('tool_call_update',toolCallId='live-tool',content=[{'type':'content','content':{'type':'text','text':'first line'}}])
            time.sleep(.4)
            update('tool_call_update',toolCallId='live-tool',status='completed')
            update('agent_message_chunk',content={'type':'text','text':'DeepSeek 实时测试完成'})
            reply(message,{'stopReason':'end_turn'})
        elif 'report-model' in text:
            update('agent_message_chunk',content={'type':'text','text':options[0]['currentValue']})
            reply(message,{'stopReason':'end_turn'})
        elif 'permission' in text:
            pending=message
            update('tool_call',toolCallId='test-tool',title='read',rawInput={'path':'README.md'},status='in_progress')
            emit({'id':'permission-1','method':'session/request_permission','params':{'sessionId':'native-deepseek','toolCall':{'toolCallId':'test-tool'},'options':[{'optionId':'yes','kind':'allow_once'},{'optionId':'no','kind':'reject_once'}]}})
        elif 'wait' in text:pending=message
        else:
            update('agent_thought_chunk',messageId='message',content={'type':'text','text':'检查协议'})
            update('agent_message_chunk',content={'type':'text','text':'DeepSeek '})
            update('agent_message_chunk',content={'type':'text','text':'测试回复'})
            update('usage_update',used=123,size=1000)
            reply(message,{'stopReason':'end_turn'})
    elif method=='session/cancel':
        if pending:reply(pending,{'stopReason':'cancelled'});pending=None
    elif message.get('id')=='permission-1' and 'result' in message:
        decision=message['result']['outcome']
        update('tool_call_update',toolCallId='test-tool',status='completed',content=[{'type':'content','content':{'type':'text','text':json.dumps(decision)}}])
        reply(pending,{'stopReason':'end_turn'});pending=None
