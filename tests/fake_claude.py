"""Deterministic subprocess for exercising the real Claude adapter wire protocol."""
import json
import sys
import time
import os
from pathlib import Path

if os.environ.get('AI_XS_TEST_WIRE_LOG'):
    prompt=''
    if '--append-system-prompt-file' in sys.argv:
        prompt=Path(sys.argv[sys.argv.index('--append-system-prompt-file')+1]).read_text()
    with Path(os.environ['AI_XS_TEST_WIRE_LOG']).open('a') as log:log.write(json.dumps({'engine':'claude','instructions':prompt})+'\n')

if '--version' in sys.argv:
    print('test Claude Code');sys.exit()

def emit(message): print(json.dumps(message),flush=True)
def finish():
    emit({'type':'stream_event','event':{'type':'message_start','message':{'id':'message-test','usage':{'input_tokens':10,'cache_read_input_tokens':5}}}})
    emit({'type':'stream_event','event':{'type':'content_block_start','index':0,'content_block':{'type':'text','text':''}}})
    emit({'type':'stream_event','event':{'type':'content_block_delta','index':0,'delta':{'type':'text_delta','text':'测试完成'}}})
    emit({'type':'assistant','uuid':'native-last','message':{'id':'message-test','content':[{'type':'text','text':'测试完成'}],'usage':{'input_tokens':10,'output_tokens':3,'cache_read_input_tokens':5}}})
    emit({'type':'result','subtype':'success','is_error':False,'usage':{'input_tokens':100,'output_tokens':30,'cache_read_input_tokens':50},'modelUsage':{'test':{'contextWindow':1000}}})

for line in sys.stdin:
    m=json.loads(line)
    if m['type']=='control_request':
        request=m['request'];kind=request['subtype']
        emit({'type':'control_response','response':{'subtype':'success','request_id':m['request_id'],'response':{'models':[{'value':'default','displayName':'测试 Claude'}]} if kind=='initialize' else {}}})
        if kind=='interrupt': emit({'type':'result','subtype':'error_during_execution','is_error':True,'errors':['interrupted diagnostic']})
    elif m['type']=='user':
        prompt=' '.join(b.get('text','') for b in m['message']['content'])
        if 'progress-demo' in prompt:
            emit({'type':'system','subtype':'status','status':'requesting'})
            emit({'type':'system','subtype':'api_retry','attempt':1,'max_retries':10,'retry_delay_ms':1000,'error_status':529,'error':'overloaded'})
            time.sleep(.15)
            emit({'type':'stream_event','event':{'type':'message_start','message':{'id':'progress-msg','usage':{}}}})
            emit({'type':'stream_event','event':{'type':'content_block_start','index':0,'content_block':{'type':'thinking','thinking':''}}})
            emit({'type':'stream_event','event':{'type':'content_block_delta','index':0,'delta':{'type':'thinking_delta','thinking':'实时思考'}}})
            emit({'type':'stream_event','event':{'type':'content_block_start','index':1,'content_block':{'type':'tool_use','id':'live-tool','name':'Bash','input':{}}}})
            emit({'type':'stream_event','event':{'type':'content_block_delta','index':1,'delta':{'type':'input_json_delta','partial_json':'{"command":"echo live"}'}}})
            emit({'type':'stream_event','event':{'type':'content_block_stop','index':1}})
            emit({'type':'tool_progress','tool_use_id':'live-tool','tool_name':'Bash','elapsed_time_seconds':2})
            time.sleep(.25)
            emit({'type':'user','message':{'content':[{'type':'tool_result','tool_use_id':'live-tool','content':'live','is_error':False}]}})
            finish();continue
        if 'wait' in prompt: continue
        if 'question' in prompt:
            emit({'type':'control_request','request_id':'question-1','request':{'subtype':'can_use_tool','tool_name':'AskUserQuestion','input':{'questions':[{'header':'方案','question':'选择方案？','multiSelect':True,'options':[{'label':'甲','description':'第一方案'},{'label':'乙','description':'第二方案'}]}]}}})
        elif 'permission' in prompt:
            emit({'type':'control_request','request_id':'permission-1','request':{'subtype':'can_use_tool','tool_name':'Bash','input':{'command':'printf test'}}})
        else: finish()
    elif m['type']=='control_response':
        response=m['response']['response']
        if response.get('behavior')=='allow':
            answers=response.get('updatedInput',{}).get('answers')
            if answers: emit({'type':'assistant','uuid':'answer-ack','message':{'id':'answer-ack','content':[{'type':'text','text':json.dumps(answers,ensure_ascii=False)}]}})
        finish()
