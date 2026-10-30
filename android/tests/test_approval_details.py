"""Approval envelopes must preserve the actual operation before rendering it."""
import copy
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from claude_bridge import ClaudeBridge
from deepseek_bridge import DeepSeekBridge


class ApprovalDetailsTests(unittest.TestCase):
    def test_claude_keeps_read_arguments_even_when_description_is_present(self):
        events=[]
        app=SimpleNamespace(approvals={},_emit=events.append)
        with tempfile.TemporaryDirectory() as directory:
            bridge=ClaudeBridge(app,directory)
            bridge._progress=lambda *a,**kw:None
            run={'data':{'id':'claude-test','cwd':'/repo','permissionMode':'ask'},'turn':{'id':'turn'}}
            arguments={'file_path':'/repo/SKILL.md','offset':10,'limit':20}
            bridge._handle(run,{'type':'control_request','request_id':'read-1','request':{
                'subtype':'can_use_tool','tool_name':'Read','input':arguments,'description':'Inspect skill',
                'decision_reason':'Permission required'}})
            params=events[-1]['params']
            self.assertEqual(params['arguments'],arguments)
            self.assertEqual(params['tool'],'Read')
            self.assertEqual(params['reason'],'Inspect skill')
            self.assertEqual(params['decisionReason'],'Permission required')
            arguments['file_path']='/changed'
            self.assertEqual(params['arguments']['file_path'],'/repo/SKILL.md')

    def test_deepseek_permission_packet_overrides_cached_arguments(self):
        events=[]
        app=SimpleNamespace(approvals={},_emit=events.append)
        with tempfile.TemporaryDirectory() as directory:
            bridge=DeepSeekBridge(app,directory)
            run={'data':{'id':'deepseek-test','cwd':'/repo','permissionMode':'ask'},
                'turn':{'id':'turn','items':[{'id':'read','tool':'Read','arguments':{'path':'/old'}}]},'requests':{}}
            bridge.runs['deepseek-test']=run
            bridge._message(run,{'id':'approval','method':'session/request_permission','params':{
                'toolCall':{'toolCallId':'read','title':'Read file','rawInput':{'path':'/actual'},
                    'locations':[{'path':'/actual','line':2}]},'options':[]}})
            params=events[-1]['params']
            self.assertEqual(params['arguments'],{'path':'/actual'})
            self.assertEqual(params['locations'][0]['path'],'/actual')
            self.assertEqual(params['tool'],'Read file')
