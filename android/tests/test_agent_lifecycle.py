"""Mobile lifecycle keeps disabled Agents disabled and does not allocate idle runtimes."""
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from engine_manager import EngineManager


class App:
    process = None
    def __init__(self): self.active_turns = {}; self.starts = 0; self.stops = 0
    def start(self): self.starts += 1
    def stop(self): self.stops += 1; self.active_turns.clear()
    def _emit(self, event): pass


class Bridge:
    def __init__(self): self.runs = {}; self.closed = 0
    def close(self): self.closed += 1; self.runs.clear()
    def model_list(self): raise AssertionError('Mobile start must not require API credentials')


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = App(); self.claude = Bridge(); self.deepseek = Bridge()
        with patch.dict(os.environ, {'AI_XS_SINGLE_AGENT': '1'}):
            self.manager = EngineManager(self.app, self.claude, self.tmp.name, self.deepseek)
        self.manager._version = lambda *args: {'installed': True, 'available': True, 'version': '1.0'}

    def tearDown(self): self.tmp.cleanup()

    def action(self, engine, action, confirmed=True):
        job = self.manager.action(engine, action, confirmed)['job']
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            job = self.manager.read_job(job['id'])['job']
            if engine not in self.manager.busy: return job
            time.sleep(.005)
        self.fail('Lifecycle operation did not finish')

    def test_start_all_is_lazy_and_preserves_another_active_agent(self):
        self.claude.runs['active'] = object()
        for engine in ('codex', 'claude', 'deepseek'):
            self.manager.enabled[engine] = False
            self.assertEqual(self.action(engine, 'start')['status'], 'completed')
        self.assertTrue(all(self.manager.enabled.values()))
        self.assertIn('active', self.claude.runs)
        self.assertEqual(self.app.starts, 0)
        self.assertEqual(self.app.stops, 0)

    def test_targeted_stop_leaves_unrelated_work_and_survives_restart(self):
        self.claude.runs['active'] = object()
        self.deepseek.runs['stop-me'] = object()
        with self.assertRaisesRegex(ValueError, '确认'):
            self.manager.action('deepseek', 'stop')
        self.assertEqual(self.action('deepseek', 'stop')['status'], 'completed')
        self.assertIn('active', self.claude.runs)
        self.assertFalse(self.deepseek.runs)
        self.assertFalse(self.manager.enabled['deepseek'])
        restarted = EngineManager(self.app, self.claude, self.tmp.name, self.deepseek)
        self.assertFalse(restarted.enabled['deepseek'])
        with self.assertRaisesRegex(RuntimeError, 'Agent 管理'):
            restarted.require_enabled('deepseek')

    def test_failed_start_does_not_disable_other_agents(self):
        self.manager._version = lambda *args: {'available': False, 'error': 'missing executable'}
        self.assertEqual(self.action('codex', 'start')['status'], 'failed')
        self.assertFalse(self.manager.enabled['codex'])
        self.assertTrue(self.manager.enabled['claude'])
        self.assertTrue(self.manager.enabled['deepseek'])

    def test_status_does_not_run_cli_probes(self):
        def forbidden(*args): raise AssertionError('Status polling launched a CLI')
        self.manager._version = forbidden
        rows = self.manager.status(probe=False)['data']
        self.assertEqual(len(rows), 3)
        self.assertTrue(all(row['state'] == 'ready' for row in rows))


if __name__ == '__main__': unittest.main()
