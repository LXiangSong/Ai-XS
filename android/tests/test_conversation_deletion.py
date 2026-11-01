"""Deletion removes owned data and reports incomplete cleanup instead of hiding it."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from native_history import remove_owned, delete_deepseek
from claude_bridge import ClaudeBridge
from deepseek_bridge import DeepSeekBridge
import test_shared_conversations as shared


class DeleteStoreTests(unittest.TestCase):
    setUp=shared.SharedConversationTests.setUp
    tearDown=shared.SharedConversationTests.tearDown
    make_store=shared.SharedConversationTests.make_store
    read_native=shared.SharedConversationTests.read_native
    call=shared.SharedConversationTests.call
    start=shared.SharedConversationTests.start
    send=shared.SharedConversationTests.send

    def test_failed_native_cleanup_keeps_record_and_retry_skips_completed_children(self):
        tid=self.start('codex');self.send(tid,'codex','saved private prompt')
        self.store.handle('switch',{'threadId':tid,'engine':'claude'});self.send(tid,'claude','second private prompt')
        doc=self.store.adopt(tid);first,second=[c['threadId'] for c in doc['children']]
        original=self.store.call
        def flaky(method,params):
            if method=='thread/delete' and params['threadId']==second:raise OSError('temporary cleanup failure')
            return original(method,params)
        self.store.call=flaky
        with self.assertRaisesRegex(RuntimeError,'未标记删除成功'):self.store.handle('thread/delete',{'threadId':tid})
        self.assertFalse(self.store.index[tid].get('deleted'));self.assertTrue(self.store._path(tid).exists())
        self.assertNotIn(first,self.native);self.assertIn(second,self.native)
        self.assertNotIn(tid,self.store.busy);self.assertNotIn(tid,self.store.deleting)
        self.store.call=original;self.store.handle('thread/delete',{'threadId':tid})
        self.assertNotIn(second,self.native);self.assertFalse(self.store._path(tid).exists())
        deletes=[p['threadId'] for m,p in self.calls if m=='thread/delete']
        self.assertEqual(deletes.count(first),1);self.assertEqual(deletes.count(second),1)
        self.assertNotIn('private prompt',(self.root/'chats'/'index.json').read_text())
        self.assertTrue(self.store.handle('thread/delete',{'threadId':tid})['deleted'])
        restored=self.make_store();self.assertFalse(restored.listing([{'id':first},{'id':second}],False))

    def test_running_conversation_is_not_partly_deleted(self):
        tid=self.start();self.send(tid,'deepseek','active')
        doc=self.store.adopt(tid);doc['turns'][-1]['status']='inProgress'
        with self.assertRaisesRegex(ValueError,'停止当前任务'):self.store.handle('thread/delete',{'threadId':tid})
        self.assertTrue(self.store._path(tid).exists())
        self.assertFalse(any(m=='thread/delete' for m,p in self.calls))

    def test_explicit_delete_of_legacy_hidden_record_also_purges_native(self):
        tid=self.start();self.send(tid,'deepseek','old hidden prompt')
        source=self.store.adopt(tid)['children'][0]['threadId']
        self.store.index[tid]['deleted']=True;self.store.cache.pop(tid,None);self.store._path(tid).unlink()
        self.store._write(self.store.folder/'index.json',self.store.index)
        self.assertIn(source,self.native)
        self.assertTrue(self.store.handle('thread/delete',{'threadId':tid})['deleted'])
        self.assertNotIn(source,self.native);self.assertTrue(self.store.index[tid]['deletionComplete'])

    def test_file_cleanup_failure_is_retryable_without_a_deleted_native_binding(self):
        tid=self.start();self.send(tid,'deepseek','retained canonical history')
        def fail(doc):raise OSError('files still locked')
        with self.assertRaisesRegex(OSError,'files still locked'):self.store.delete({'threadId':tid},fail)
        doc=self.store.adopt(tid);self.assertIsNone(doc['binding']);self.assertTrue(doc['turns'])
        self.assertTrue(self.store._path(tid).exists());self.assertFalse(self.store.index[tid].get('deleted'))
        self.assertTrue(self.store.delete({'threadId':tid})['deleted'])


class NativeHistoryDeletionTests(unittest.TestCase):
    def test_claude_and_deepseek_delete_exact_native_files_and_keep_other_sessions(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);app=type('App',(),{'_emit':lambda *a:None,'approvals':{}})()
            with patch.dict(os.environ,{'HOME':temp,'CLAUDE_CONFIG_DIR':str(root/'.claude'),'DSH_HOME':str(root/'.dsh')}):
                for engine,Bridge in [('claude',ClaudeBridge),('deepseek',DeepSeekBridge)]:
                    bridge=Bridge(app,root/'state'/engine)
                    thread=bridge.call('thread/start',{'cwd':temp})['thread'];data=bridge._read(thread['id']);native=data['nativeSession']
                    home=root/('.claude' if engine=='claude' else '.dsh')
                    project=home/('projects' if engine=='claude' else 'sessions')/'project';project.mkdir(parents=True)
                    if engine=='claude':
                        artifact=project/(native+'.jsonl');artifact.write_text('private session')
                        child=project/native/'subagents';child.mkdir(parents=True);(child/'agent.jsonl').write_text('private subagent')
                    else:
                        artifact=project/native;artifact.mkdir();(artifact/'session.v4.jsonl.zstd').write_text('compressed private session')
                    keep=project/('unrelated.jsonl' if engine=='claude' else 'unrelated');keep.write_text('keep other history')
                    history=home/'history.jsonl';history.write_text(json.dumps({'sessionId':native,'display':'private history'})+'\n'+json.dumps({'sessionId':'other','display':'keep history'})+'\n')
                    result=bridge.call('thread/delete',{'threadId':thread['id']})
                    self.assertTrue(result['deleted']);self.assertFalse(artifact.exists());self.assertTrue(keep.exists())
                    self.assertFalse(bridge._path(thread['id']).exists())
                    self.assertNotIn('private history',history.read_text());self.assertIn('keep history',history.read_text())
                    if engine=='claude':self.assertFalse((project/native).exists())

    def test_cleanup_does_not_follow_links_or_accept_parent_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'owned';root.mkdir();outside=Path(temp)/'project';outside.mkdir()
            keep=outside/'source.txt';keep.write_text('project source')
            link=root/'link';link.symlink_to(outside,target_is_directory=True)
            with self.assertRaises(ValueError):remove_owned(root,root/'..')
            remove_owned(root,link);self.assertTrue(keep.exists());self.assertFalse(link.is_symlink())
            with self.assertRaises(ValueError):delete_deepseek('../project','thread')


if __name__=='__main__':unittest.main()
