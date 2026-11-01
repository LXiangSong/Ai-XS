import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec=importlib.util.spec_from_file_location('agent_package',Path(__file__).parents[1]/'runtime/agent_package.py')
package=importlib.util.module_from_spec(spec);spec.loader.exec_module(package)


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
    def tearDown(self):self.temp.cleanup()
    def bundle(self,agent='codex',arch='linux-arm64',bad_hash=False):
        data=io.BytesIO()
        with tarfile.open(fileobj=data,mode='w:gz') as tar:
            content=b'agent executable';entry=tarfile.TarInfo('bin/codex');entry.size=len(content);entry.mode=0o755;tar.addfile(entry,io.BytesIO(content))
        payload=data.getvalue();m={'format':1,'agent':agent,'version':'1.2.3','architecture':arch,'sha256':'0'*64 if bad_hash else hashlib.sha256(payload).hexdigest()}
        path=self.root/'agent.zip'
        with zipfile.ZipFile(path,'w') as z:z.writestr('manifest.json',json.dumps(m));z.writestr('payload.tar.gz',payload)
        return path
    def test_valid_manifest_and_payload(self):
        path=self.bundle();self.assertEqual(package.bundle_manifest(path,'codex')['version'],'1.2.3')
        with patch.object(package,'check'),patch.object(package,'promote') as promote:
            package.import_bundle(path,'codex',self.root/'agents');self.assertEqual(promote.call_count,1)
            self.assertEqual(promote.call_args.args[-2:],('codex','1.2.3'))
    def test_wrong_hash_architecture_and_agent_are_rejected(self):
        for arguments in [{'bad_hash':True},{'arch':'linux-x64'},{'agent':'claude'}]:
            with self.subTest(arguments=arguments),self.assertRaises(ValueError):package.bundle_manifest(self.bundle(**arguments),'codex')
    def test_tar_cannot_write_outside_installation(self):
        for name,link in [('../escaped',None),('/escaped',None),('link','../../escaped')]:
            path=self.root/'bad.tar.gz'
            with tarfile.open(path,'w:gz') as tar:
                entry=tarfile.TarInfo(name)
                if link:entry.type=tarfile.SYMTYPE;entry.linkname=link;tar.addfile(entry)
                else:entry.size=1;tar.addfile(entry,io.BytesIO(b'x'))
            destination=self.root/'install';destination.mkdir(exist_ok=True)
            with self.subTest(name=name),self.assertRaises((ValueError,tarfile.FilterError)):package.extract_payload(path,destination)
            self.assertFalse((self.root/'escaped').exists())
    def test_npm_failure_keeps_existing_installation(self):
        base=self.root/'agents';agent=base/'codex';agent.mkdir(parents=True);(agent/'installed.json').write_text('old');(agent/'current').symlink_to('releases/old')
        with patch.object(package.subprocess,'run',side_effect=subprocess.CalledProcessError(1,['npm'])):
            with self.assertRaises(subprocess.CalledProcessError):package.npm_install('codex','1.2.3',base)
        self.assertEqual((agent/'installed.json').read_text(),'old');self.assertEqual((agent/'current').readlink(),Path('releases/old'));self.assertFalse(list(agent.glob('.install-*')))
    def test_claude_native_package_launches_without_postinstall_scripts(self):
        base=self.root/'agents'
        data=io.BytesIO()
        with tarfile.open(fileobj=data,mode='w:gz') as tar:
            script=('#!'+sys.executable+'\nprint("1.2.3 (Claude Code)")\n').encode()
            item=tarfile.TarInfo('package/claude');item.size=len(script);tar.addfile(item,io.BytesIO(script))
        payload=data.getvalue();import base64
        metadata={'dist':{'tarball':'https://registry.npmjs.org/native.tgz','integrity':'sha512-'+base64.b64encode(hashlib.sha512(payload).digest()).decode()}}
        real_run=subprocess.run
        def download_or_run(command,**kwargs):
            if command[0]!='curl':return real_run(command,**kwargs)
            Path(command[command.index('-o')+1]).write_bytes(payload)
            return subprocess.CompletedProcess(command,0)
        with patch.object(package.urllib.request,'urlopen',return_value=io.BytesIO(json.dumps(metadata).encode())),patch.object(package.subprocess,'run',side_effect=download_or_run),patch.object(package,'promote') as promote:
            package.npm_install('claude','1.2.3',base)
            self.assertEqual(promote.call_count,1)
        self.assertFalse(list((base/'claude').glob('.install-*')))
    def test_bad_version_is_not_activated(self):
        prefix=self.root/'prefix';(prefix/'bin').mkdir(parents=True);(prefix/'bin/codex').write_text('placeholder')
        with patch.object(package.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'codex-cli 9.0.0','')):
            with self.assertRaisesRegex(ValueError,'版本'):package.check(prefix,'codex','1.2.3')
    def test_native_tarball_stays_on_trusted_domestic_mirror(self):
        path='/@anthropic-ai/claude-code-linux-arm64/-/package.tgz'
        self.assertEqual(package.native_tarball_url('https://registry.npmjs.org'+path),'https://registry.npmmirror.com'+path)
        self.assertEqual(package.native_tarball_url('https://registry.npmmirror.com'+path),'https://registry.npmmirror.com'+path)
        for url in ['http://registry.npmmirror.com/pkg.tgz','https://registry.npmmirror.com.evil.test/pkg.tgz','https://registry.npmmirror.com@evil.test/pkg.tgz']:
            with self.subTest(url=url),self.assertRaises(ValueError):package.native_tarball_url(url)
    def test_npm_install_uses_domestic_registry(self):
        stage=self.root/'stage';stage.mkdir()
        with patch.object(package.subprocess,'run') as run,patch.object(package,'check'):
            package.populate_prefix(stage,'deepseek','1.2.3')
        self.assertIn('--registry=https://registry.npmmirror.com',run.call_args.args[0])
    def test_version_cannot_traverse_install_directories(self):
        for version in ['../escape','/absolute','x/../../y','x;echo hello']:
            with self.subTest(version=version),self.assertRaises(ValueError):package.validate('codex',version)
    def test_export_import_preserves_relative_launch_symlink(self):
        prefix=self.root/'prefix';(prefix/'bin').mkdir(parents=True);(prefix/'lib').mkdir()
        script=prefix/'lib/cli.py';script.write_text('#!'+sys.executable+'\nprint("codex-cli 1.2.3")\n');script.chmod(0o755)
        (prefix/'bin/codex').symlink_to('../lib/cli.py')
        artifact=self.root/'codex.zip'
        subprocess.run([sys.executable,str(Path(__file__).parents[1]/'tools/make_agent_bundle.py'),'codex','1.2.3',str(prefix),str(artifact)],check=True,capture_output=True)
        with patch.object(package,'promote') as promote:
            package.import_bundle(artifact,'codex',self.root/'agents')
            self.assertEqual(promote.call_count,1)

    def release(self, base, version, suffix):
        folder=base/'codex'/'releases'/(version+'-'+suffix)
        (folder/'bin').mkdir(parents=True)
        script=folder/'bin/codex';script.write_text('#!'+sys.executable+'\nprint("codex-cli '+version+'")\n');script.chmod(0o755)
        return folder

    def test_select_old_version_preserves_new_version_and_user_data(self):
        base=self.root/'agents';old=self.release(base,'1.2.3','aabbccdd');new=self.release(base,'1.3.0','11223344')
        launch=self.root/'launchers'
        package.activate_release(base,'codex',new,'1.3.0',launch)
        project=self.root/'project.txt';project.write_text('keep me')
        package.select_version(base,'codex',old.name,launch)
        self.assertEqual((base/'codex/current').resolve(),old)
        self.assertEqual(json.loads((base/'codex/installed.json').read_text())['version'],'1.2.3')
        self.assertEqual(subprocess.check_output([str(launch/'codex')],text=True).strip(),'codex-cli 1.2.3')
        self.assertTrue(new.is_dir());self.assertEqual(project.read_text(),'keep me')
        rows=package.versions(base,'codex')['data'];self.assertEqual(rows[0]['version'],'1.2.3');self.assertTrue(rows[0]['current'])
        package.select_version(base,'codex',new.name,launch)
        package.remove_version(base,'codex',old.name)
        self.assertFalse(old.exists());self.assertTrue(new.exists())
        with self.assertRaisesRegex(ValueError,'当前'):package.remove_version(base,'codex',new.name)

    def test_failed_rollback_leaves_active_version_unchanged(self):
        base=self.root/'agents';old=self.release(base,'1.2.3','aabbccdd');new=self.release(base,'1.3.0','11223344')
        package.activate_release(base,'codex',new,'1.3.0',self.root/'launch')
        (old/'bin/codex').write_text('#!'+sys.executable+'\nprint("wrong version")\n')
        with self.assertRaisesRegex(ValueError,'版本'):package.select_version(base,'codex',old.name,self.root/'launch')
        self.assertEqual((base/'codex/current').resolve(),new)
        self.assertEqual(json.loads((base/'codex/installed.json').read_text())['version'],'1.3.0')

    def test_version_manager_rejects_traversal_symlinks_and_active_metadata(self):
        base=self.root/'agents';old=self.release(base,'1.2.3','aabbccdd')
        folder=base/'codex';(folder/'releases/link').symlink_to(old,target_is_directory=True)
        for value in ['../current','/tmp','link','missing']:
            with self.subTest(value=value),self.assertRaises(ValueError):package.release_path(base,'codex',value)
        # Protect active metadata even if the current pointer was damaged.
        (folder/'installed.json').write_text(json.dumps({'release':old.name,'version':'1.2.3'}))
        with self.assertRaisesRegex(ValueError,'当前'):package.remove_version(base,'codex',old.name)
        self.assertEqual(len(package.versions(base,'codex')['data']),1)

    def test_promote_records_version_and_keeps_prior_release(self):
        base=self.root/'agents';old=self.release(base,'1.2.3','aabbccdd');stage=base/'codex'/'.install-test';stage.mkdir()
        with patch.object(package,'activate_release') as activate:
            package.promote(stage,base,'codex','1.3.0')
        selected=activate.call_args.args[2]
        self.assertEqual(json.loads((selected/'.aixs-release.json').read_text())['version'],'1.3.0')
        self.assertTrue(old.is_dir());self.assertEqual(package.release_version(selected),'1.3.0')


if __name__=='__main__':unittest.main()
