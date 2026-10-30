import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile
from extension_manager import ExtensionManager, github_source, unpack
from mcp_manager import McpManager

SKILL='---\nname: demo\ndescription: Regression skill\n---\nRead resources/example.txt before use.\n'

def archive(files):
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w') as z:
        for name,value in files.items():z.writestr(name,value)
    return out.getvalue()

class ExtensionTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.calls=[]
        self.zip=archive({'repo-main/skills/demo/SKILL.md':SKILL,'repo-main/skills/demo/resources/example.txt':'resource'})
        def download(url,limit):
            self.calls.append(url)
            return json.dumps({'default_branch':'main'}).encode() if '/repos/' in url else self.zip
        self.manager=ExtensionManager(self.root,home=self.root,downloader=download)
    def install(self,agents=None):
        preview=self.manager.preview({'url':'owner/repo'})
        return self.manager.install({'previewId':preview['previewId'],'candidateId':preview['data'][0]['id'],'agents':agents or ['codex']})['extension']
    def test_github_preview_default_branch_complete_resources_and_restart(self):
        item=self.install(['all'])
        self.assertEqual(len(self.calls),2)
        self.assertEqual((Path(item['path'])/'resources/example.txt').read_text(),'resource')
        new=ExtensionManager(self.root)
        self.assertEqual(new.listing()['data'][0]['agents'],['codex','claude','deepseek'])
        for engine in ('codex','claude','deepseek'):self.assertIn('demo',new.instructions(engine))
    def test_agent_assignment_stop_disable_and_remove_are_persistent(self):
        item=self.install(['deepseek'])
        self.assertEqual(self.manager.instructions('codex'),'')
        self.assertIn('demo',self.manager.instructions('deepseek'))
        self.manager.configure({'id':item['id'],'agents':['claude']})
        self.assertEqual(self.manager.instructions('deepseek'),'')
        self.assertIn('demo',self.manager.instructions('claude'))
        self.manager.configure({'id':item['id'],'agents':[]})
        self.assertEqual(self.manager.instructions('claude'),'')
        self.manager.remove(item['id'])
        self.assertFalse(Path(item['path']).exists());self.assertEqual(self.manager.listing()['data'],[])
    def test_claude_hooks_plugin_rejects_incompatible_agents(self):
        self.zip=archive({'repo-main/.claude-plugin/plugin.json':json.dumps({'name':'hook-demo','hooks':'./hooks/hooks.json'}),
                          'repo-main/hooks/hooks.json':'{}','repo-main/skills/demo/SKILL.md':SKILL})
        preview=self.manager.preview({'url':'owner/repo'})
        self.assertEqual(len(preview['data']),1)
        item=preview['data'][0]
        self.assertTrue(item['compatibility']['claude']['supported'])
        for engine in ['codex','deepseek']:
            self.assertFalse(item['compatibility'][engine]['supported'])
            with self.assertRaisesRegex(ValueError,'hooks'):
                self.manager.install({'previewId':preview['previewId'],'candidateId':item['id'],'agents':[engine]})
        installed=self.manager.install({'previewId':preview['previewId'],'candidateId':item['id'],'agents':['claude']})['extension']
        self.assertEqual(self.manager.claude_plugins(),[installed['path']])
        self.assertEqual(self.manager.instructions('claude'),'')
    def test_portable_skill_mcp_plugin_isolated_by_agent_and_removed_from_codex(self):
        self.zip=archive({'repo-main/.codex-plugin/plugin.json':json.dumps({'name':'portable','skills':'./skills'}),
                          'repo-main/.mcp.json':json.dumps({'mcpServers':{'example':{'command':'python','args':['${CODEX_PLUGIN_ROOT}/server.py']}}}),
                          'repo-main/server.py':'','repo-main/skills/demo/SKILL.md':SKILL})
        item=self.install(['codex','deepseek'])
        servers=self.manager.mcp_servers('codex');key=next(iter(servers))
        self.assertEqual(servers[key]['args'],[item['path']+'/server.py'])
        self.assertEqual(self.manager.mcp_servers('claude'),{})
        class App:
            process=object()
            def __init__(self):self.calls=[]
            def call(self,method,params):self.calls.append((method,params));return {}
        (self.root/'mcp-servers.json').write_text('{}')
        app=App();mcp=McpManager(app,self.root);mcp.extensions=self.manager
        mcp.sync_codex();count=len(app.calls);mcp.sync_codex();self.assertEqual(count,len(app.calls))
        self.assertEqual(mcp.acp_servers()[0]['name'],key)
        self.manager.configure({'id':item['id'],'agents':['claude']})
        mcp.prepare_claude();self.assertIn(key,json.loads(mcp.claude_path.read_text())['mcpServers'])
        mcp.sync_codex()
        self.assertTrue(any(p.get('keyPath')=='mcp_servers.'+key+'.enabled' and p.get('value') is False for _,p in app.calls))
        self.assertEqual(mcp.acp_servers(),[])
    def test_local_flat_zip_and_single_markdown_import(self):
        for name,payload in [('local.zip',archive({'SKILL.md':SKILL,'resources/example.txt':'resource'})),('SKILL.md',SKILL.encode())]:
            path=self.root/name;path.write_bytes(payload)
            preview=self.manager.import_local({'path':str(path)})
            self.assertEqual(preview['data'][0]['name'],'demo')
    def test_local_directory_import_copies_resources_and_rejects_symlink(self):
        folder=self.root/'source';folder.mkdir();(folder/'SKILL.md').write_text(SKILL);(folder/'extra').write_text('data')
        preview=self.manager.import_local({'path':str(folder)})
        item=self.manager.install({'previewId':preview['previewId'],'candidateId':preview['data'][0]['id'],'agents':['all']})['extension']
        self.assertEqual((Path(item['path'])/'extra').read_text(),'data')
        (folder/'bad').symlink_to('/etc/passwd')
        with self.assertRaisesRegex(ValueError,'符号链接'):self.manager.import_local({'path':str(folder)})
    def test_archive_path_escape_and_symlink_are_rejected(self):
        for bad in ['/absolute','repo/../../escape','repo\\escape']:
            with self.assertRaises(ValueError):unpack(archive({bad:'bad'}),self.root/'bad')
        out=io.BytesIO()
        with zipfile.ZipFile(out,'w') as z:
            i=zipfile.ZipInfo('repo/link');i.external_attr=(stat.S_IFLNK|0o777)<<16;z.writestr(i,'/etc/passwd')
        with self.assertRaisesRegex(ValueError,'符号链接'):unpack(out.getvalue(),self.root/'bad')
        self.assertFalse((self.root/'escape').exists())
    def test_invalid_source_and_no_extensions_do_not_install_anything(self):
        for value in ['http://github.com/owner/repo','https://evil.test/owner/repo','https://user:pass@github.com/owner/repo','https://github.com/owner/repo/tree/main/../escape']:
            with self.assertRaises(ValueError):github_source(value)
        self.zip=archive({'repo-main/README.md':'Not an extension'})
        with self.assertRaisesRegex(ValueError,'未找到'):self.manager.preview({'url':'owner/repo'})
        self.assertEqual(self.manager.listing()['data'],[])
    def test_tree_url_subdir_and_commit(self):
        source=github_source('https://github.com/owner/repo/tree/main/skills/demo')
        self.assertEqual((source['ref'],source['subdir']),('main','skills/demo'))
        preview=self.manager.preview({'url':source['url'],'ref':'a1b2c3','subdir':source['subdir']})
        self.assertEqual(len(preview['data']),1);self.assertIn('/zip/a1b2c3',self.calls[-1])
    def test_unresolved_mcp_variables_are_not_reported_portable(self):
        self.zip=archive({'repo-main/.codex-plugin/plugin.json':json.dumps({'name':'needs-config','mcpServers':{'demo':{'url':'https://example.test/${API_KEY}'}}})})
        preview=self.manager.preview({'url':'owner/repo'})
        self.assertFalse(preview['data'][0]['compatibility']['codex']['supported'])
    def test_import_cannot_recursively_copy_extension_management_parent(self):
        with self.assertRaisesRegex(ValueError,'具体 Skill'):
            self.manager.import_local({'path':str(self.root)})
