"""API profiles belong to Ai-XS and never inherit a host account on first setup."""
import json
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest
from provider_manager import ProviderManager


@contextmanager
def model_endpoint(status=200, body=None):
    received=[]
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            received.append((self.path, dict(self.headers)))
            self.send_response(status)
            self.send_header('Content-Type','application/json')
            self.end_headers()
            self.wfile.write(json.dumps(body if body is not None else {'data':[{'id':'test-model'}]}).encode())
        def log_message(self,*args):
            pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:
        yield 'http://127.0.0.1:'+str(server.server_port),received
    finally:
        server.shutdown();server.server_close();thread.join()


class ProviderProfileTests(unittest.TestCase):
    def test_claude_gateway_version_prefix_is_not_duplicated_or_applied_to_other_agents(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary);manager=ProviderManager(folder,home=folder,ubuntu_home=folder)
            profile={'mode':'thirdparty','name':'Gateway','baseUrl':'https://gateway.invalid/prefix/v1',
                     'protocol':'anthropic-messages','models':['test-model'],'key':'private-test'}
            manager.save('claude',profile)
            settings=json.loads((folder/'claude-provider.json').read_text())
            self.assertEqual(settings['env']['ANTHROPIC_BASE_URL'],'https://gateway.invalid/prefix')
            self.assertEqual(manager.selected('claude')['baseUrl'],profile['baseUrl'])
            profile['protocol']='responses';manager.save('codex',profile)
            args,_=manager.codex_launch()
            self.assertIn(profile['baseUrl'],' '.join(args))
            profile['protocol']='anthropic-messages';manager.save('deepseek',profile)
            self.assertIn(profile['baseUrl'],(folder/'deepseek-provider.yml').read_text())

    def test_codex_efforts_follow_catalog_and_preserve_future_native_values(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary);manager=ProviderManager(folder,home=folder,ubuntu_home=folder)
            model=manager.model_list('codex',{})['data'][0]
            efforts=[e['reasoningEffort'] for e in model['supportedReasoningEfforts']]
            self.assertIn('max',efforts);self.assertIn('xhigh',efforts)
            native={'data':[{'id':model['model'],'model':model['model'],
                'supportedReasoningEfforts':[{'reasoningEffort':'new-effort'}]}]}
            manager.model_list('codex',native)
            self.assertEqual(manager.model_list('codex',{})['data'][0]['supportedReasoningEfforts'],[{'reasoningEffort':'new-effort'}])

    def discover_at(self, base, **extra):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary)
            manager=ProviderManager(folder/'state',home=folder,ubuntu_home=folder)
            return manager.discover('codex',dict(mode='thirdparty',name='Test gateway',baseUrl=base+'/v1',
                protocol='responses',models=['test-model'],key='test-discovery-secret',**extra))

    def test_discovery_identifies_app_to_api_gateway(self):
        with model_endpoint() as (base,received):
            result=self.discover_at(base)
            self.assertEqual(result['models'],['test-model'])
            path,headers=received[0]
            self.assertEqual(path,'/v1/models')
            self.assertTrue(headers['User-Agent'].startswith('Ai-XS/'))
            self.assertEqual(headers['Accept'],'application/json')
            self.assertEqual(headers['Authorization'],'Bearer test-discovery-secret')

    def test_discovery_preserves_explicit_user_agent_case_insensitively(self):
        with model_endpoint() as (base,received):
            self.discover_at(base,headers={'user-agent':'Custom integration'})
            self.assertEqual(received[0][1]['User-Agent'],'Custom integration')

    def test_firewall_rejection_is_distinct_from_bad_credentials(self):
        with model_endpoint(403,{'error_name':'browser_signature_banned','detail':'test-discovery-secret'}) as (base,received):
            with self.assertRaisesRegex(ValueError,'Cloudflare / 防火墙') as error:
                self.discover_at(base)
            self.assertNotIn('test-discovery-secret',str(error.exception))
            self.assertEqual(len(received),1)

    def test_unauthorized_key_reports_authentication_failure(self):
        with model_endpoint(401,{'error':'invalid key'}) as (base,_):
            with self.assertRaisesRegex(ValueError,'密钥认证失败'):
                self.discover_at(base)

    def test_new_profiles_ignore_host_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary);home=folder/'home';home.mkdir()
            (home/'.codex').mkdir()
            (home/'.codex/auth.json').write_text(json.dumps({'OPENAI_API_KEY':'host-secret'}))
            (home/'.codex/config.toml').write_text('model_provider="host"\n[model_providers.host]\nbase_url="https://host.invalid/v1"\nexperimental_bearer_token="host-secret"\n')
            (home/'.claude').mkdir()
            (home/'.claude/settings.json').write_text(json.dumps({'env':{'ANTHROPIC_API_KEY':'host-secret','ANTHROPIC_BASE_URL':'https://host.invalid'}}))
            manager=ProviderManager(folder/'state',home=home,ubuntu_home=home)
            for engine in ['codex','claude','deepseek']:
                value=manager.read(engine)
                self.assertEqual(value['mode'],'thirdparty')
                for profile in value['profiles'].values():
                    self.assertFalse(profile['keyConfigured'])
                self.assertEqual(manager.selected(engine)['baseUrl'],'')
            self.assertNotIn('host-secret',manager.path.read_text())

    def test_existing_api_profiles_survive_service_restart(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary);home=folder/'home';home.mkdir()
            manager=ProviderManager(folder/'state',home=home,ubuntu_home=home)
            profile={'mode':'thirdparty','name':'Test provider','baseUrl':'https://provider.invalid',
                     'key':'persisted-profile-secret','models':['test-model'],'protocol':'anthropic-messages'}
            manager.save('claude',profile)
            reloaded=ProviderManager(folder/'state',home=home,ubuntu_home=home)
            self.assertEqual(reloaded.selected('claude')['key'],profile['key'])
            public=reloaded.read('claude')
            self.assertTrue(public['profiles']['thirdparty']['keyConfigured'])
            self.assertNotIn(profile['key'],json.dumps(public))
