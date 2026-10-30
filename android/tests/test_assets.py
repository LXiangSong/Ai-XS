import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[2]


class AssetTests(unittest.TestCase):
    def test_backend_asset_is_portable_and_excludes_private_state(self):
        subprocess.run([sys.executable,str(ROOT/'android/tools/prepare_app_assets.py')],check=True,capture_output=True)
        path=ROOT/'android/app/src/main/assets/runtime/native.zip'
        with zipfile.ZipFile(path) as archive:
            names=archive.namelist()
            self.assertIn('native_host.py',names)
            self.assertIn('agent_core.py',names)
            self.assertIn('provider_manager.py',names)
            self.assertTrue(all('/' not in name and not name.startswith('.') for name in names))
            self.assertNotIn('providers.json',names)
            self.assertFalse(any(name.endswith(('.html','.css','.js')) for name in names))
            self.assertNotIn(b'ThreadingHTTPServer',archive.read('agent_core.py'))
            self.assertNotIn(b'BaseHTTPRequestHandler',archive.read('agent_core.py'))
        self.assertFalse((ROOT/'android/app/src/main/assets/runtime/web.zip').exists())


if __name__=='__main__':unittest.main()
