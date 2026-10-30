"""Read installed extensions for each native assistant without borrowing Codex APIs."""
import json
import os
from pathlib import Path
import re

UBUNTU_HOME = Path.home()


def extensions(engine, cwd):
    project = Path(cwd).resolve()
    home = Path(os.environ.get('AI_XS_ENGINE_HOME',str(UBUNTU_HOME)))
    agent_folder = {'codex':'.codex','claude':'.claude','deepseek':'.dsh'}[engine]
    folders = [project/agent_folder/'skills',project/'.agents/skills',home/agent_folder/'skills',home/'.agents/skills']
    skills = {};errors=[]
    for folder in folders:
        try: paths = sorted([*folder.glob('*/SKILL.md'),*folder.glob('*.md')])
        except OSError as error: errors.append(str(error));continue
        for path in paths:
            try:
                text=path.read_text()[:65536]
                front=text.split('---',2)[1] if text.startswith('---') and text.count('---')>=2 else ''
                name=re.search(r'^name:\s*(.+)$',front,re.M);description=re.search(r'^description:\s*(.+)$',front,re.M)
                label=name.group(1).strip(' \"\'') if name else path.parent.name if path.name=='SKILL.md' else path.stem
                if label not in skills:skills[label]={'name':label,'path':str(path),'description':description.group(1).strip(' \"\'') if description else '', 'enabled':True}
            except (OSError,UnicodeError) as error:errors.append(str(error))
    plugins=[]
    if engine=='claude':
        file=home/'.claude/plugins/installed_plugins.json'
        if file.exists():
            try:
                for name,items in json.loads(file.read_text()).get('plugins',{}).items():
                    for item in items if isinstance(items,list) else [items]:
                        plugins.append({'name':name,'version':item.get('version',''),'path':item.get('installPath','')})
            except (OSError,ValueError) as error:errors.append(str(error))
    elif engine=='deepseek':
        file=home/'.dsh/profiles/acp/package.json'
        if file.exists():
            try:
                value=json.loads(file.read_text());plugins=[{'name':name,'version':str(version)} for name,version in value.get('dependencies',{}).items()]
                for name in value.get('dsh',{}).get('profile',{}).get('bundles',[]):
                    if not any(p['name']==name for p in plugins):plugins.append({'name':name,'version':'profile bundle'})
            except (OSError,ValueError) as error:errors.append(str(error))
    return {'engine':engine,'skills':list(skills.values()),'plugins':plugins,'errors':errors,
        'detail':'三个 Agent 共用手机工具；Skills 和插件按各自安装目录读取。'}
