"""DSH native accounting beyond its context-only ACP notification."""
import copy
import ctypes
import ctypes.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from deepseek_usage import SessionUsage, normalize
from deepseek_bridge import DeepSeekBridge


def compressed(raw):
    lib=ctypes.CDLL(ctypes.util.find_library('zstd') or 'libzstd.so.1')
    lib.ZSTD_compressBound.argtypes=[ctypes.c_size_t];lib.ZSTD_compressBound.restype=ctypes.c_size_t
    lib.ZSTD_compress.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_size_t,ctypes.c_int]
    lib.ZSTD_compress.restype=ctypes.c_size_t
    source=ctypes.create_string_buffer(raw);dest=ctypes.create_string_buffer(lib.ZSTD_compressBound(len(raw)))
    size=lib.ZSTD_compress(dest,len(dest),source,len(raw),1)
    return dest.raw[:size]


def row(kind,data=None,seq=1):return {'type':kind,'seq':seq,'time':seq*1000,'data':data or {}}
def header(session='native'):return {'type':'session','id':session,'version':4,'createdAt':0}
def usage(turn,step,input=10,output=2,cache=3,kind='assistant/message',seq=3):
    return row(kind,{'turn':turn,'step':step,'usage':{'inputTokens':input,'outputTokens':output,'cacheReadTokens':cache}},seq)
def lines(rows):return b''.join(json.dumps(r).encode()+b'\n' for r in rows)


class DeepSeekUsageTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
    def tearDown(self):self.tmp.cleanup()

    def test_compressed_frames_partial_tail_dedup_retries_and_models(self):
        path=self.root/'session.v4.jsonl.zstd'
        path.write_bytes(compressed(lines([header(),row('request/context',{'model':'thirdparty/a','contextWindow':1000}),
            usage(1,1,output=1,kind='assistant/attempt'),usage(1,1)])))
        reader=SessionUsage(path,'native');first=reader.read()
        self.assertEqual(first['tokenUsage']['total']['totalTokens'],15)
        self.assertEqual(first['tokenUsage']['last']['totalTokens'],13)
        self.assertEqual(reader.read(),first)
        addition=compressed(lines([row('llm/retry-started',{'turn':1,'step':1}),usage(1,1,input=20),
            row('request/context',{'model':'thirdparty/b','contextWindow':2000}),usage(2,1,input=30,seq=8)]))
        with path.open('ab') as f:f.write(addition[:len(addition)//2])
        reader.read() # A half-written frame is normal while the Agent is active.
        with path.open('ab') as f:f.write(addition[len(addition)//2:])
        final=reader.read();models={g['model']:g for g in final['tokenUsage']['byModel']}
        self.assertEqual(models['thirdparty/a']['total']['totalTokens'],40)
        self.assertEqual(models['thirdparty/b']['total']['totalTokens'],35)
        self.assertEqual(final['tokenUsage']['total']['totalTokens'],75)
        self.assertEqual(final['model'],'thirdparty/b')
        self.assertEqual(final['tokenUsage']['last']['totalTokens'],33)
        self.assertEqual(final['tokenUsage']['modelContextWindow'],2000)
        self.assertEqual(SessionUsage(path,'native').read(),final)
        self.assertEqual(reader.read(),final)

    def test_stream_usage_missing_fields_compaction_and_file_replacement(self):
        path=self.root/'session.v4.jsonl'
        events=[header(),row('request/context',{'model':'any/model','contextWindow':100}),
            row('assistant/message',{'turn':1,'step':1,'stream':[
                {'type':'chunk','chunk':{'type':'usage','usage':{'inputTokens':10,'outputTokens':2,'cacheReadTokens':5,'cacheWriteTokens':3,'reasoningTokens':1}}}]}),
            row('compaction/end')]
        path.write_bytes(lines(events));reader=SessionUsage(path,'native');result=reader.read()
        self.assertEqual(result['tokenUsage']['total']['totalTokens'],20)
        self.assertEqual(result['tokenUsage']['total']['inputTokens'],18)
        self.assertEqual(result['tokenUsage']['total']['reasoningOutputTokens'],1)
        self.assertTrue(result['contextStale'])
        replacement=self.root/'new';replacement.write_bytes(lines([header(),row('request/context',{'model':'new','contextWindow':200}),usage(1,1,input=1,cache=0)]))
        replacement.replace(path)
        self.assertEqual(reader.read()['tokenUsage']['total']['totalTokens'],3)
        self.assertEqual(normalize({}),{})
        self.assertEqual(normalize({'inputTokens':True,'outputTokens':None}),{})

    def test_missing_usage_stays_unknown_and_rejects_wrong_session(self):
        path=self.root/'session.v4.jsonl';path.write_bytes(lines([header(),row('request/context',{'model':'any/model','contextWindow':1000}),
            row('assistant/message',{'turn':1,'step':1,'stream':[]})]))
        result=SessionUsage(path,'native').read()
        self.assertEqual(result['tokenUsage']['total'],{});self.assertEqual(result['tokenUsage']['last'],{})
        with self.assertRaisesRegex(ValueError,'标识'):SessionUsage(path,'other').read()

    def test_bridge_reads_native_without_acp_and_preserves_old_native_sessions(self):
        with patch.dict(os.environ,{'DSH_HOME':str(self.root/'custom-dsh')}):
            events=[];app=type('App',(),{'_emit':lambda _,e:events.append(e),'approvals':{}})()
            bridge=DeepSeekBridge(app,self.root/'bridge')
            thread=bridge.call('thread/start',{'cwd':str(self.root),'model':'first'})['thread'];data=bridge._read(thread['id'])
            first=data['nativeSession'];second='another-native'
            for session,model in [(first,'first'),(second,'second')]:
                folder=self.root/'custom-dsh'/'sessions'/'project'/session;folder.mkdir(parents=True)
                (folder/'session.v4.jsonl.zstd').write_bytes(compressed(lines([header(session),row('request/context',{'model':model,'contextWindow':1000}),usage(1,1)])))
            bridge._save(data);one=bridge.call('usage/read',{'threadId':thread['id']})
            self.assertEqual(one['tokenUsage']['total']['totalTokens'],15)
            data=bridge._read(thread['id']);data.update(nativeSession=second,nativeSessions=[first,second],model='second');bridge._save(data)
            two=bridge.call('usage/read',{'threadId':thread['id']})
            self.assertEqual(two['tokenUsage']['total']['totalTokens'],30)
            self.assertEqual({g['model'] for g in two['tokenUsage']['byModel']},{'first','second'})
            restarted=DeepSeekBridge(app,self.root/'bridge')
            self.assertEqual(restarted.call('usage/read',{'threadId':thread['id']}),two)
            self.assertIsNone(bridge._dsh_session_path('../project'))


if __name__=='__main__':unittest.main()
