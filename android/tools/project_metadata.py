"""Shared version metadata and the source files that belong in a public checkout."""
from pathlib import Path
import os
import re

ROOT = Path(__file__).resolve().parents[2]
EXCLUDED = {'.git', '.state', '.uploads', '.gradle', '__pycache__', 'build', 'dist'}
GENERATED = {
    'android/local.properties',
    'android/app/src/main/assets/runtime/native.zip',
    'android/app/src/main/assets/runtime/catalog.json',
    'android/app/src/main/assets/runtime/agent_package.py',
    'android/app/src/main/assets/runtime/backend_control.py',
}


def version(root=ROOT):
    values = {}
    for line in (root / 'android/version.properties').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip()
    name = values.get('versionName', '')
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', name):
        raise ValueError('Invalid versionName in android/version.properties')
    code = int(values['versionCode'])
    if code <= 0:
        raise ValueError('versionCode must be positive')
    return {'versionName': name, 'versionCode': code}


def source_files(root=ROOT):
    for folder, directories, names in os.walk(root, followlinks=False):
        directories[:] = sorted(d for d in directories if d not in EXCLUDED)
        for directory in directories:
            path = Path(folder) / directory
            if path.is_symlink():
                raise ValueError('Source directory must not be a symlink: ' + str(path.relative_to(root)))
        for name in sorted(names):
            path = Path(folder) / name
            relative = path.relative_to(root).as_posix()
            if relative.startswith('docs/verify/') or relative in GENERATED:
                continue
            if name.startswith('.env') and name != '.env.example':
                raise ValueError('Private environment file in source tree: ' + relative)
            if path.suffix in {'.apk', '.pyc', '.p12', '.jks', '.keystore', '.pem', '.key'}:
                raise ValueError('Private/generated file in source tree: ' + relative)
            if path.is_symlink():
                raise ValueError('Source file must not be a symlink: ' + relative)
            yield path
