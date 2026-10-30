"""Per-attempt stdio proxy for built-in tools, enforcing reserved device/browser scopes."""
import json
from pathlib import Path
import sys
import urllib.request


def forward(config, request):
    method=request.get('method');params=request.get('params',{})
    if method=='tools/call':
        name=params.get('name','')
        if name.startswith('phone_') and not config.get('device'):raise ValueError('当前子任务未预留手机控制资源，请暂停并修改任务资源')
        if name.startswith('browser_') and not config.get('browser'):raise ValueError('当前子任务未预留浏览器资源，请暂停并修改任务资源')
        if name.startswith('extensions_') and name not in ('extensions_list',):raise ValueError('协作执行中不能修改共享扩展，请在设置中管理')
        params.setdefault('arguments',{})['_aixs']=config['context']
    headers={'Content-Type':'application/json',**config.get('headers',{})}
    req=urllib.request.Request(config['url'],data=json.dumps(request).encode(),headers=headers)
    with urllib.request.urlopen(req,timeout=3700) as response:
        if response.status==202:return None
        result=json.load(response)
    if method=='tools/list':
        tools=result.get('result',{}).get('tools',[])
        result['result']['tools']=[t for t in tools if
            (not t['name'].startswith('phone_') or config.get('device')) and
            (not t['name'].startswith('browser_') or config.get('browser')) and
            (not t['name'].startswith('extensions_') or t['name']=='extensions_list')]
    return result


def main():
    config=json.loads(Path(sys.argv[1]).read_text())
    for raw in sys.stdin:
        req={}
        try:
            req=json.loads(raw);reply=forward(config,req)
        except Exception as error:
            reply={'jsonrpc':'2.0','id':req.get('id'),'error':{'code':-32000,'message':str(error)}}
        if reply is not None and 'id' in req:print(json.dumps(reply,ensure_ascii=False),flush=True)


if __name__=='__main__':main()
