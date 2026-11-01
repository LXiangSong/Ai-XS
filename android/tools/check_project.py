#!/usr/bin/env python3
"""Check portable sources, local documentation links and pinned runtime assets."""
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import unquote
from project_metadata import ROOT, source_files, version


def check(root=ROOT):
    metadata = version(root)
    files = list(source_files(root))
    errors = []
    for path in files:
        if path.suffix != '.md':
            continue
        for target in re.findall(r'\[[^\]\n]*\]\(([^)\n]+)\)', path.read_text()):
            target = target.strip().split(' "', 1)[0].strip('<>')
            if re.match(r'[a-zA-Z][\w+.-]*:', target) or target.startswith('#'):
                continue
            target = unquote(target.split('#', 1)[0])
            if target and not (path.parent / target).exists():
                errors.append(f'{path.relative_to(root)}: missing link {target}')
    manifest = json.loads((root / 'android/app/src/main/assets/runtime/manifest.json').read_text())
    expected = [('android/app/src/main/assets/runtime/ubuntu-base.bin', manifest['archiveSha256'])]
    expected += [('android/app/src/main/jniLibs/arm64-v8a/' + row['file'], row['sha256']) for row in manifest['nativeFiles']]
    for relative, checksum in expected:
        path = root / relative
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
            errors.append('Runtime asset missing or modified: ' + relative)
    if manifest.get('rootfsCount') != 1 or manifest.get('architecture') != 'arm64-v8a':
        errors.append('Expected exactly one shared ARM64 rootfs')
    if errors:
        raise ValueError('\n'.join(errors))
    print(f'Project checks passed: {len(files)} source files, version {metadata["versionName"]}, runtime hashes and documentation links.')
    return metadata


if __name__ == '__main__':
    check()
