"""Guard source portability and metadata consumed by Android and the backend."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from project_metadata import source_files, version


class ProjectMetadataTests(unittest.TestCase):
    def test_version_is_shared_and_rejects_invalid_release_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder);(root/'android').mkdir()
            path = root/'android/version.properties'
            path.write_text('versionName=1.2.3-alpha\nversionCode=60\n')
            self.assertEqual(version(root), {'versionName':'1.2.3-alpha','versionCode':60})
            for value in ('versionName=../bad\nversionCode=60', 'versionName=1.2.3\nversionCode=0'):
                path.write_text(value)
                with self.assertRaises(ValueError):version(root)

    def test_private_state_generated_assets_and_screenshots_are_not_source_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for name in ('source.py','.state/private.json','.git/config','dist/release.apk',
                         'android/app/build/generated.java','android/app/src/main/assets/runtime/native.zip',
                         'docs/verify/device.png'):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('fixture')
            self.assertEqual([p.relative_to(root).as_posix() for p in source_files(root)],['source.py'])

    def test_a_source_symlink_to_another_machine_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'source.py').symlink_to('/missing/device/temp.py')
            with self.assertRaisesRegex(ValueError,'symlink'):list(source_files(root))

    def test_symlink_directory_cannot_hide_sources_outside_the_checkout(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'source').symlink_to(Path(folder).parent, target_is_directory=True)
            with self.assertRaisesRegex(ValueError,'symlink'):list(source_files(root))

    def test_private_environment_files_cannot_be_published(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'.env').write_text('fixture')
            with self.assertRaisesRegex(ValueError,'Private environment'):list(source_files(root))


if __name__=='__main__':unittest.main()
