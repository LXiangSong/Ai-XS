#!/usr/bin/env python3
"""Package shared Agent adapters only, without HTML, CSS, JS UI or an HTTP server."""
from pathlib import Path
import json
import zipfile
from project_metadata import version

ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'android/app/src/main/assets/runtime'
MODULES=['file_reports.py','realtime_events.py','dsh_live_events.mjs','native_usage.py','deepseek_usage.py','native_history.py','conversation_usage.py','collaboration_session.py','collaboration_transcript.py','collaboration_store.py','execution_profiles.py','worker_pool.py','task_scheduler.py','collaboration_service.py','collaboration_worker.py','collaboration_workspace.py','collaboration_mcp.py','assistant_tools.py','extension_manager.py','claude_bridge.py','deepseek_bridge.py','shared_conversations.py','codex_model_catalog.json',
    'permission_modes.py','engine_manager.py','local_tools.py','mcp_manager.py','provider_manager.py','rpc_process.py']
MODULES.append('native_children.py')
MODULES+=['collaboration_policy.py','collaboration_api.py']
MODULES.append('conversation_queue.py')
MODULES.append('conversation_execution.py')


def main():
    ASSETS.mkdir(parents=True,exist_ok=True)
    core=(ROOT/'agent_core.py').read_text()
    if 'class Handler(' in core or 'ThreadingHTTPServer' in core:
        raise ValueError('Native Agent core must not include an HTTP server')
    with zipfile.ZipFile(ASSETS/'native.zip','w',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('agent_core.py',core)
        archive.writestr('version.json', json.dumps(version(), sort_keys=True) + '\n')
        archive.write(ROOT/'android/runtime/native_host.py','native_host.py')
        for name in MODULES:archive.write(ROOT/name,name)
    print('Packaged native Agent service; no webpage or HTTP listener.')


if __name__=='__main__':main()
