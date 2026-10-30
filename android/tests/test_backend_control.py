import importlib.util
from pathlib import Path
import subprocess
import sys
import time
import unittest

spec=importlib.util.spec_from_file_location('control',Path(__file__).parents[1]/'runtime/backend_control.py')
control=importlib.util.module_from_spec(spec);spec.loader.exec_module(control)


class BackendControlTests(unittest.TestCase):
    def test_cleanup_matches_only_internal_server_script(self):
        # The path is passed as a separate argv entry, as in RuntimeManager.
        internal=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)',control.TARGET.decode()])
        unrelated=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)','server.py'])
        try:
            time.sleep(.05)
            self.assertTrue(control.matches(internal.pid))
            self.assertFalse(control.matches(unrelated.pid))
            self.assertFalse(control.matches(__import__('os').getpid()))
        finally:
            for process in [internal,unrelated]:process.terminate();process.wait(timeout=3)
