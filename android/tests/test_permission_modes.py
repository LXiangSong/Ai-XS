"""Permission choices must affect execution and remain scoped to the correct session."""
from pathlib import Path
import tempfile
import unittest
from permission_modes import PermissionModes, apply


class PermissionModeTests(unittest.TestCase):
    def test_defaults_sessions_and_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            store=PermissionModes(folder)
            self.assertEqual(store.mode('deepseek'),'ask')
            store.set('deepseek','auto')
            store.set('deepseek','ask','session-one')
            reloaded=PermissionModes(folder)
            self.assertEqual(reloaded.mode('deepseek'),'auto')
            self.assertEqual(reloaded.mode('deepseek','session-one'),'ask')
            self.assertEqual(reloaded.mode('claude'),'ask')
            with self.assertRaises(ValueError):store.set('deepseek','unknown')
            self.assertEqual(reloaded.mode('deepseek'),'auto')

    def test_codex_uses_each_native_request_policy_shape(self):
        for mode,policy in [('ask','on-request'),('auto','never')]:
            start=apply({},mode,'thread/start','codex')
            turn=apply({},mode,'turn/start','codex')
            self.assertEqual(start,{'approvalPolicy':policy,'sandbox':'danger-full-access'})
            self.assertEqual(turn,{'approvalPolicy':policy,'sandboxPolicy':{'type':'dangerFullAccess'}})

    def test_other_agents_receive_explicit_mode(self):
        for engine in ['claude','deepseek']:
            inputs={'model':'test-model'}
            for mode,policy in [('ask','on-request'),('auto','never')]:
                out=apply(inputs,mode,'turn/start',engine)
                self.assertEqual(out['permissionMode'],mode)
                self.assertEqual(out['approvalPolicy'],policy)
                self.assertEqual(out['sandbox'],'danger-full-access')
            self.assertEqual(inputs,{'model':'test-model'})
