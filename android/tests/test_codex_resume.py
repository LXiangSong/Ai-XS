"""Codex history recovery after another Agent releases its idle process."""
import importlib.util
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
spec=importlib.util.spec_from_file_location('resume_native_host',ROOT/'android/runtime/native_host.py')
native=importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class CodexResumeTests(unittest.TestCase):
    def setUp(self):
        self.host=native.NativeHost(io.StringIO())
        self.loaded=False
        self.calls=[]

    def tearDown(self):
        self.host.pool.shutdown(wait=True)

    def route(self,method,params):
        self.calls.append((method,params))
        if method=='thread/read':
            if not self.loaded:raise RuntimeError('thread not loaded')
            return {'thread':{'id':'saved','cwd':'/projects/demo'}}
        if method=='thread/resume':self.loaded=True;return {}
        if method=='thread/list':return {'data':[],'nextCursor':None}
        if method=='thread/turns/list':return {'data':[{'id':'second'},{'id':'first'}],'nextCursor':None}
        self.fail('Unexpected call: '+method)

    def test_local_index_restores_history_without_provider_filtered_listing(self):
        with patch.object(self.host,'codex_record',return_value={'id':'saved','cwd':'/projects/demo'}), \
                patch.object(native.core,'api_route',side_effect=self.route):
            value=self.host.read_thread({'threadId':'saved'})
        self.assertEqual([turn['id'] for turn in value['thread']['turns']],['first','second'])
        self.assertFalse(any(method=='thread/list' for method,_ in self.calls))
        self.assertIn(('thread/resume',{'threadId':'saved','cwd':'/projects/demo'}),self.calls)

    def test_unindexed_rollout_is_resumed_before_treating_reference_as_missing(self):
        with patch.object(self.host,'codex_record',return_value=None), \
                patch.object(native.core,'api_route',side_effect=self.route):
            result=self.host.read_thread({'threadId':'saved','cwd':'/projects/demo'})
        self.assertEqual(result['thread']['id'],'saved')
        self.assertTrue(all(params['modelProviders']==[] for method,params in self.calls if method=='thread/list'))

    def test_only_confirmed_missing_rollout_returns_missing_selection(self):
        original=self.route
        def route(method,params):
            if method=='thread/resume':raise RuntimeError('no rollout found for thread id saved')
            return original(method,params)
        with patch.object(self.host,'codex_record',return_value=None), \
                patch.object(native.core,'api_route',side_effect=route):
            result=self.host.read_thread({'threadId':'saved'})
        self.assertTrue(result['threadMissing'])
        self.assertEqual(result['missingThreadId'],'saved')
        self.assertIsNone(result['thread'])

    def test_startup_and_storage_failures_do_not_clear_session_selection(self):
        for record in (None,{'id':'saved','cwd':'/projects/demo'}):
            for message in ('Codex connection timed out','permission denied',
                            'project directory does not exist','no rollout found for thread id saved'):
                if record is None and message.startswith('no rollout'):continue
                with self.subTest(record=record,error=message):
                    def route(method,params):
                        if method=='thread/resume':raise RuntimeError(message)
                        return self.route(method,params)
                    with patch.object(self.host,'codex_record',return_value=record), \
                            patch.object(native.core,'api_route',side_effect=route), \
                            self.assertRaisesRegex(RuntimeError,'Codex 会话恢复失败'):
                        self.host.read_thread({'threadId':'saved'})


if __name__=='__main__':unittest.main()
