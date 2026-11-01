"""Claude child streams and parent streams share a UI but never share block state."""
import copy
import tempfile
import unittest
from types import SimpleNamespace
from claude_bridge import ClaudeBridge


class ClaudeStreamTests(unittest.TestCase):
    def test_single_block_snapshots_do_not_overwrite_sibling_thinking_and_text(self):
        self.stream('message_start',message={'id':'message'})
        self.stream('content_block_start',index=0,content_block={'type':'thinking','thinking':'Thought'})
        self.stream('content_block_start',index=1,content_block={'type':'text','text':'Answer'})
        for block in ({'type':'thinking','thinking':'Thought'},{'type':'text','text':'Answer'}):
            self.bridge._handle(self.run,{'type':'assistant','message':{'id':'message','content':[block]}})
        self.assertEqual([(i['id'],i['type']) for i in self.run['turn']['items']],[('message-0','reasoning'),('message-1','agentMessage')])
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.events=[]
        self.bridge=ClaudeBridge(SimpleNamespace(_emit=lambda e:self.events.append(copy.deepcopy(e)),approvals={}),self.tmp.name)
        self.bridge._save=lambda *args:None
        self.bridge._usage_message=lambda *args:None
        self.run={'data':{'id':'claude-test','cwd':self.tmp.name},'turn':{'id':'t','items':[]},'blocks':{}}

    def tearDown(self):self.tmp.cleanup()

    def stream(self,kind,parent=None,**fields):
        self.bridge._handle(self.run,{'type':'stream_event','parent_tool_use_id':parent,'event':{'type':kind,**fields}})

    def test_child_and_parent_thinking_and_tool_arguments_update_while_in_progress(self):
        for parent,msg in ((None,'parent-message'),('subtask-tool','child-message')):
            self.stream('message_start',parent,message={'id':msg})
            self.stream('content_block_start',parent,index=0,content_block={'type':'thinking','thinking':''})
        self.stream('content_block_delta',None,index=0,delta={'type':'thinking_delta','thinking':'Parent thinks'})
        self.stream('content_block_delta','subtask-tool',index=0,delta={'type':'thinking_delta','thinking':'Child thinks'})
        self.stream('content_block_start','subtask-tool',index=1,content_block={'type':'tool_use','id':'read-child','name':'Read','input':{}})
        self.stream('content_block_delta','subtask-tool',index=1,delta={'type':'input_json_delta','partial_json':'{"file_path":"/tmp/'})
        items={i['id']:i for i in self.run['turn']['items']}
        self.assertEqual(items['parent-message-0']['content'],['Parent thinks'])
        self.assertEqual(items['child-message-0']['content'],['Child thinks'])
        self.assertEqual(items['child-message-0']['parentToolUseId'],'subtask-tool')
        self.assertIn('/tmp/',items['read-child']['partialInput'])
        self.assertEqual(items['read-child']['status'],'inProgress')
        self.bridge._handle(self.run,{'type':'tool_progress','tool_use_id':'read-child','tool_name':'Read','elapsed_time_seconds':2,'parent_tool_use_id':'subtask-tool'})
        self.assertEqual(items['read-child']['elapsedSeconds'],2)
        self.assertTrue(any(e['method']=='item/reasoning/textDelta' and e['params']['itemId']=='child-message-0' for e in self.events))

    def test_tool_progress_without_prior_start_and_system_task_lifecycle_are_visible(self):
        self.bridge._handle(self.run,{'type':'tool_progress','tool_use_id':'late','tool_name':'Bash','elapsed_time_seconds':1})
        self.assertEqual(self.run['turn']['items'][0]['status'],'inProgress')
        self.bridge._handle(self.run,{'type':'system','subtype':'task_started','task_id':'one','description':'Inspect files'})
        self.bridge._handle(self.run,{'type':'system','subtype':'task_progress','task_id':'one','summary':'Read three files'})
        item=self.run['turn']['items'][1]
        self.assertEqual(item['type'],'taskProgress');self.assertEqual(item['progressMessage'],'Read three files');self.assertEqual(item['status'],'inProgress')
        self.bridge._handle(self.run,{'type':'system','subtype':'task_notification','task_id':'one','status':'failed','summary':'Read failed'})
        self.assertEqual(item['status'],'failed');self.assertTrue(any(e['method']=='item/completed' and e['params']['item']['id']=='claude-task:one' for e in self.events))

    def test_retry_discards_uncommitted_tool_draft_and_full_result_reuses_card(self):
        self.stream('message_start',message={'id':'first'})
        self.stream('content_block_start',index=0,content_block={'type':'tool_use','id':'draft','name':'Edit','input':{}})
        self.stream('content_block_delta',index=0,delta={'type':'input_json_delta','partial_json':'{"file_path"'})
        self.stream('message_start',message={'id':'retry'})
        self.stream('content_block_start',index=0,content_block={'type':'tool_use','id':'real','name':'Edit','input':{}})
        self.bridge._handle(self.run,{'type':'assistant','message':{'id':'retry','content':[{'type':'tool_use','id':'real','name':'Edit','input':{'file_path':'a.txt','new_string':'ok'}}]}})
        self.bridge._handle(self.run,{'type':'user','message':{'content':[{'type':'tool_result','tool_use_id':'real','content':'success'}]}})
        first,real=self.run['turn']['items']
        self.assertEqual(first['status'],'discarded');self.assertIn('未执行',first['tool'])
        self.assertEqual(real['status'],'completed');self.assertEqual(real['type'],'dynamicToolCall')
        self.assertEqual(real['partialInput'],'');self.assertEqual(real['arguments']['file_path'],'a.txt')

    def test_background_task_can_finish_with_only_task_updated_patch(self):
        self.bridge._handle(self.run,{'type':'system','subtype':'task_started','task_id':'background','description':'Build files','task_type':'agent'})
        item=self.run['turn']['items'][0]
        self.bridge._handle(self.run,{'type':'system','subtype':'task_updated','task_id':'background','patch':{'result':'dist/app.apk'}})
        self.assertEqual(item['status'],'inProgress');self.assertEqual(item['progressMessage'],'Build files')
        self.bridge._handle(self.run,{'type':'system','subtype':'task_updated','task_id':'background','patch':{'status':'completed'}})
        self.assertEqual(item['status'],'completed');self.assertEqual(item['result'],'dist/app.apk')
        self.bridge._handle(self.run,{'type':'system','subtype':'task_progress','task_id':'background','description':'late progress'})
        self.assertEqual(item['status'],'completed');self.assertEqual(len(self.run['turn']['items']),1)
        self.assertTrue(any(e['method']=='item/completed' and e['params']['item']['id']=='claude-task:background' for e in self.events))

    def test_hook_service_tool_rate_limit_and_context_reset_are_visible(self):
        for subtype in ('hook_started','hook_response'):
            self.bridge._handle(self.run,{'type':'system','subtype':subtype,'hook_id':'h','hook_event':'PreToolUse','exit_code':0,'stdout':'hook done'})
        self.assertEqual(self.run['turn']['items'][0]['status'],'completed')
        self.bridge._handle(self.run,{'type':'assistant','message':{'id':'m','content':[
            {'type':'server_tool_use','id':'remote-tool','name':'web_search','input':{'query':'docs'}},
            {'type':'web_search_tool_result','tool_use_id':'remote-tool','content':'found'}]}})
        tool=next(i for i in self.run['turn']['items'] if i['id']=='remote-tool')
        self.assertEqual(tool['status'],'completed');self.assertEqual(tool['result'],'found')
        self.bridge._handle(self.run,{'type':'rate_limit_event','rate_limit_info':{'status':'rejected','resetsAt':123}})
        self.assertEqual(self.run['turn']['items'][-1]['status'],'failed')
        self.bridge._handle(self.run,{'type':'conversation_reset','new_conversation_id':'replacement'})
        self.assertEqual(self.run['data']['nativeSession'],'replacement')

    def test_child_final_message_never_becomes_parent_final_answer(self):
        self.bridge._handle(self.run,{'type':'assistant','parent_tool_use_id':'parent-tool','message':{'id':'child-message',
            'content':[{'type':'text','text':'Child final'}]}})
        item=self.run['turn']['items'][0]
        self.assertEqual(item['type'],'subagentMessage');self.assertEqual(item['parentToolUseId'],'parent-tool')
        self.assertEqual(self.events[-1]['params']['item']['type'],'subagentMessage')

    def test_root_completion_waits_for_background_task_terminal_notification(self):
        self.bridge._refresh_native_usage=lambda data:None
        self.bridge.runs[self.run['data']['id']]=self.run
        self.bridge._handle(self.run,{'type':'system','subtype':'task_started','task_id':'background','task_type':'agent'})
        self.bridge._finish(self.run,'completed',cleanup=False)
        self.assertFalse(any(e['method']=='turn/completed' for e in self.events))
        self.bridge._handle(self.run,{'type':'system','subtype':'task_notification','task_id':'background','status':'completed'})
        self.assertEqual(len([e for e in self.events if e['method']=='turn/completed']),1)
