#!/usr/bin/env python3
"""Package only reviewed application source; never copy .state or user HOME."""
import json
from pathlib import Path
import shutil
from prepare_native_assets import main as prepare_native

ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'android/app/src/main/assets/runtime'


def main():
    ASSETS.mkdir(parents=True,exist_ok=True)
    catalog={'node':{'version':'v24.22.1','sha256':'dbabf7a1cbf947e6059ffad2c27b80041db97dea1e553b62afdee7f13fca336e'},
        'agents':{'codex':{'name':'Codex','version':'0.165.0'},'claude':{'name':'Claude Code','version':'2.1.312'},'deepseek':{'name':'DeepSeek Harness','version':'0.2.1-rc.2'}}}
    (ASSETS/'catalog.json').write_text(json.dumps(catalog,indent=2)+'\n')
    for name in ['agent_package.py','backend_control.py']:
        shutil.copyfile(ROOT/'android/runtime'/name,ASSETS/name)
    (ASSETS/'workbench_check.py').unlink(missing_ok=True)
    (ASSETS/'web.zip').unlink(missing_ok=True)
    prepare_native()


if __name__=='__main__':main()
