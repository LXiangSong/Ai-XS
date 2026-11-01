"""Recovery across missing events, model switches and delayed native writes."""
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from native_usage import UsageFile
from claude_bridge import ClaudeBridge


class NativeUsageTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.path=self.root/'usage.jsonl'

    def tearDown(self):self.tmp.cleanup()

    def write(self, rows, mode='w'):
        with self.path.open(mode) as stream:
            for row in rows:stream.write(json.dumps(row)+'\n')

    def tokens(self, total, last, output=2):
        return {'type':'event_msg','timestamp':'2026-10-30T01:00:00Z','payload':{'type':'token_count','info':{
            'total_token_usage':{'input_tokens':total,'output_tokens':output},
            'last_token_usage':{'input_tokens':last,'output_tokens':2},'model_context_window':1000}}}

    def message(self, mid='m1', model='gateway/old', input=10, output=2):
        return {'type':'assistant','timestamp':'2026-10-30T01:00:00Z','message':{'id':mid,'model':model,
            'usage':{'input_tokens':input,'cache_read_input_tokens':5,'output_tokens':output}}}

    def test_codex_switch_back_duplicates_partial_tail_restart_and_compaction(self):
        self.write([{'type':'turn_context','payload':{'model':'gateway/old'}},self.tokens(10,10),
            {'type':'turn_context','payload':{'model':'gateway/new'}},self.tokens(30,20,5),self.tokens(30,20,5)])
        reader=UsageFile(self.path,'codex');result=reader.read()
        models={r['model']:r for r in result['tokenUsage']['byModel']}
        self.assertEqual(models['gateway/old']['total']['totalTokens'],12)
        self.assertEqual(models['gateway/new']['total']['totalTokens'],23)
        tail=json.dumps(self.tokens(35,5,6))
        self.write([{'type':'turn_context','payload':{'model':'gateway/old'}}],'a')
        with self.path.open('a') as f:f.write(tail[:40])
        self.assertEqual(reader.read()['tokenUsage']['total']['totalTokens'],35)
        with self.path.open('a') as f:f.write(tail[40:]+'\n')
        self.write([{'type':'compacted','timestamp':'2026-10-30T01:00:01Z'}],'a')
        result=reader.read();self.assertTrue(result['contextStale'])
        self.assertEqual(result,UsageFile(self.path,'codex').read())
        models={r['model']:r for r in result['tokenUsage']['byModel']}
        self.assertEqual(models['gateway/old']['total']['totalTokens'],18)
        self.assertEqual(result['tokenUsage']['total']['totalTokens'],41)
        self.assertTrue(models['gateway/old']['contextStale'])

    def test_codex_inherited_usage_and_missing_model_stay_unattributed(self):
        self.write([{'type':'turn_context','payload':{'model':'new/model'}},self.tokens(100,10,20)])
        result=UsageFile(self.path,'codex').read()
        models={r['model']:r for r in result['tokenUsage']['byModel']}
        self.assertEqual(models[None]['total']['totalTokens'],108)
        self.assertEqual(models['new/model']['total']['totalTokens'],12)
        self.write([{'type':'event_msg','payload':{'type':'token_count','info':{'last_token_usage':{'input_tokens':7}}}}])
        result=UsageFile(self.path,'codex').read()
        self.assertEqual(result['tokenUsage']['total'],{})
        self.assertTrue(result['totalsPartial'])

    def test_claude_parser_deduplicates_chunks_models_and_compaction(self):
        self.write([self.message(),self.message(output=4),dict(self.message('side'),isSidechain=True),
            self.message('m2','new/model'),{'type':'system','subtype':'compact_boundary'}])
        result=UsageFile(self.path,'claude').read()
        self.assertEqual(result['tokenUsage']['total']['totalTokens'],36)
        self.assertTrue(result['contextStale'])
        self.assertEqual(len(result['tokenUsage']['byModel']),2)

    def test_claude_recovers_saved_session_custom_home_without_double_counting_live(self):
        session=str(uuid.uuid4());folder=self.root/'config/projects/project';folder.mkdir(parents=True)
        self.path=folder/(session+'.jsonl');self.write([self.message()])
        app=type('App',(),{'_emit':lambda *args:None,'approvals':{}})()
        with patch.dict(os.environ,{'CLAUDE_CONFIG_DIR':str(self.root/'config')}):
            bridge=ClaudeBridge(app,self.root/'state')
            tid=bridge.call('thread/start',{'cwd':str(self.root),'model':'gateway/old'})['thread']['id']
            data=bridge._read(tid);data['nativeSession']=session;bridge._save(data)
            usage=bridge.call('usage/read',{'threadId':tid})
            self.assertEqual(usage['tokenUsage']['total']['totalTokens'],17)
            data=bridge._read(tid);bridge._refresh_native_usage(data,checkpoint=True)
            run={'data':data,'lastUsage':{'input_tokens':10,'cache_read_input_tokens':5,'output_tokens':3}}
            bridge._usage_message(run,{'id':'m2','model':'new/model','usage':run['lastUsage']})
            bridge._usage_update(run,{'usage':run['lastUsage']})
            # SDK reports before native file flushes: repeated reads and a
            # service restart must not turn its later log entry into a new bill.
            self.assertEqual(bridge.call('usage/read',{'threadId':tid})['tokenUsage']['total']['totalTokens'],35)
            self.write([self.message('m2','new/model',output=3)],'a')
            restored=ClaudeBridge(app,self.root/'state')
            self.assertEqual(restored.call('usage/read',{'threadId':tid})['tokenUsage']['total']['totalTokens'],35)
            data=restored._read(tid);restored._refresh_native_usage(data,checkpoint=True)
            self.write([self.message('m3','new/model',input=20,output=4)],'a')
            usage=restored.call('usage/read',{'threadId':tid})
            self.assertEqual(usage['tokenUsage']['total']['totalTokens'],64)
            self.assertEqual(usage,restored.call('usage/read',{'threadId':tid}))

    def test_claude_legacy_aggregate_is_replaced_without_duplication(self):
        session=str(uuid.uuid4());folder=self.root/'config/projects/project';folder.mkdir(parents=True)
        self.path=folder/(session+'.jsonl');self.write([self.message()])
        app=type('App',(),{'_emit':lambda *args:None})()
        with patch.dict(os.environ,{'CLAUDE_CONFIG_DIR':str(self.root/'config')}):
            bridge=ClaudeBridge(app,self.root/'state')
            tid=bridge.call('thread/start',{'cwd':str(self.root)})['thread']['id']
            data=bridge._read(tid);data.update(nativeSession=session,tokenUsage={'total':{'inputTokens':30,'outputTokens':4,'totalTokens':34}})
            bridge._save(data)
            usage=bridge.call('usage/read',{'threadId':tid})
            self.assertEqual(usage['tokenUsage']['total']['totalTokens'],34)
            self.assertTrue(usage['totalsPartial'])
            self.assertEqual(len(usage['tokenUsage']['byModel']),2)

    def test_claude_live_native_recovery_then_stream_and_result_bill_once(self):
        session=str(uuid.uuid4());folder=self.root/'config/projects/project';folder.mkdir(parents=True)
        self.path=folder/(session+'.jsonl');self.write([self.message()])
        app=type('App',(),{'_emit':lambda *args:None})()
        with patch.dict(os.environ,{'CLAUDE_CONFIG_DIR':str(self.root/'config')}):
            bridge=ClaudeBridge(app,self.root/'state')
            tid=bridge.call('thread/start',{'cwd':str(self.root)})['thread']['id']
            data=bridge._read(tid);data['nativeSession']=session
            bridge._refresh_native_usage(data,checkpoint=True)
            run={'data':data,'usageBaseline':copy.deepcopy(data['tokenUsage']),'lastUsage':{}}
            bridge.runs[tid]=run
            self.write([self.message('m2','gateway/new',output=3)],'a')
            self.assertEqual(bridge.call('usage/read',{'threadId':tid})['tokenUsage']['total']['totalTokens'],35)
            usage={'input_tokens':10,'cache_read_input_tokens':5,'output_tokens':3}
            run['lastUsage']=usage
            bridge._usage_message(run,{'id':'m2','model':'gateway/new','usage':usage})
            bridge._usage_update(run,{'usage':usage})
            self.assertEqual(data['tokenUsage']['total']['totalTokens'],35)
            self.assertEqual(bridge.call('usage/read',{'threadId':tid})['tokenUsage']['total']['totalTokens'],35)


if __name__=='__main__':unittest.main()
