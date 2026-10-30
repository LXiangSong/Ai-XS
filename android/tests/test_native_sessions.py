"""Claude native session discovery, adoption and deletion tombstones."""
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
import sys
sys.path.insert(0, str(ROOT))

from claude_bridge import ClaudeBridge


class NativeSessionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name)
        project = self.home / 'projects' / 'demo'
        project.mkdir(parents=True)
        store = self.home / '.claude' / 'projects' / '-native-test'
        store.mkdir(parents=True)
        self.native = '11111111-2222-4333-8444-555555555555'
        rows = [
            {'type': 'user', 'sessionId': self.native, 'cwd': str(project),
             'timestamp': '2026-01-02T03:04:05.000Z', 'uuid': 'u1',
             'message': {'role': 'user', 'content': '检查原生会话'}},
            {'type': 'assistant', 'sessionId': self.native, 'cwd': str(project),
             'timestamp': '2026-01-02T03:04:06.000Z', 'uuid': 'a1',
             'message': {'id': 'm1', 'role': 'assistant', 'model': 'test-model',
                         'content': [{'type': 'text', 'text': '原生回复'}],
                         'usage': {'input_tokens': 10, 'output_tokens': 2,
                                   'cache_read_input_tokens': 5}}},
        ]
        (store / (self.native + '.jsonl')).write_text(
            '\n'.join(json.dumps(row, ensure_ascii=False) for row in rows))
        self.app = type('App', (), {'_emit': lambda *args: None, 'approvals': {}})()
        env = {'HOME': str(self.home)}
        self.patch = patch.dict(os.environ, env)
        self.patch.start()

    def tearDown(self):
        self.patch.stop()
        self.tmp.cleanup()

    def bridge(self):
        return ClaudeBridge(self.app, self.home / 'state' / 'claude')

    def test_native_session_is_listed_adopted_and_deleted(self):
        bridge = self.bridge()
        listing = bridge.call('thread/list', {'limit': 100, 'archived': False})['data']
        native = next(item for item in listing if item.get('nativeOnly'))
        self.assertEqual(native['nativeSession'], self.native)
        self.assertIn('检查原生会话', native['preview'])

        thread = bridge.call('thread/read', {'threadId': native['id']})['thread']
        self.assertEqual(thread['nativeSession'], self.native)
        turns = bridge.call('thread/turns/list', {'threadId': native['id'], 'limit': 100,
                                                  'sortDirection': 'desc', 'itemsView': 'full'})['data']
        self.assertEqual(len(turns), 1)
        self.assertTrue(any(item.get('text') == '原生回复' for item in turns[0]['items']))
        usage = bridge.call('usage/read', {'threadId': native['id']})
        self.assertEqual(usage['tokenUsage']['total']['inputTokens'], 15)
        self.assertEqual(usage['tokenUsage']['total']['outputTokens'], 2)

        bridge.call('thread/delete', {'threadId': native['id']})
        listing = bridge.call('thread/list', {'limit': 100, 'archived': False})['data']
        self.assertFalse(any(item.get('nativeSession') == self.native for item in listing))
        self.assertIn(self.native, (self.home / 'state' / 'claude' / 'native-ignored.json').read_text())

    def test_slow_native_discovery_does_not_block_saved_sessions(self):
        bridge=self.bridge();gate=threading.Event();started=threading.Event();done=threading.Event()
        saved=bridge.call('thread/start',{'cwd':str(self.home/'projects'/'demo'),'model':'test'})['thread']
        original=bridge._scan_native_sessions
        def scan():
            started.set();gate.wait(timeout=5)
            return original()
        bridge._scan_native_sessions=scan
        bridge.app._emit=lambda *args:done.set()
        try:
            listing=bridge.call('thread/list',{'nativeDiscovery':'background'})['data']
            self.assertTrue(started.wait(timeout=1))
            self.assertIn(saved['id'],[row['id'] for row in listing])
            self.assertFalse(any(row.get('nativeOnly') for row in listing))
            # Repeat reads during the scan return the same saved sessions.
            self.assertTrue(bridge.call('thread/list',{'nativeDiscovery':'background'})['data'])
        finally:gate.set()
        self.assertTrue(done.wait(timeout=3))
        listing=bridge.call('thread/list',{'nativeDiscovery':'background'})['data']
        self.assertTrue(any(row.get('nativeOnly') for row in listing))


if __name__ == '__main__':
    unittest.main()
