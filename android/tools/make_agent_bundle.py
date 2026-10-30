#!/usr/bin/env python3
"""Export an agent's dedicated installed prefix, never a HOME directory."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import zipfile


def main():
    p=argparse.ArgumentParser();p.add_argument('agent',choices=['codex','claude','deepseek']);p.add_argument('version');p.add_argument('prefix',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    root=a.prefix.resolve()
    if not(root/'bin').is_dir() or not(root/'lib').is_dir():raise SystemExit('Use the dedicated npm prefix containing bin/ and lib/, not HOME')
    with tempfile.TemporaryDirectory() as folder:
        payload=Path(folder)/'payload.tar.gz'
        with tarfile.open(payload,'w:gz',dereference=False) as archive:
            for path in sorted(root.rglob('*')):
                if path.is_dir() and not path.is_symlink():continue
                if not path.resolve().is_relative_to(root):raise SystemExit('A link escapes the package prefix')
                if path.name in ['.npmrc','auth.json','providers.json','token']:raise SystemExit('Credential-like file found; review the prefix before exporting')
                entry=archive.gettarinfo(str(path),arcname=str(path.relative_to(root)))
                if entry.issym():
                    if Path(entry.linkname).is_absolute():entry.linkname=os.path.relpath(path.resolve(),path.parent)
                    archive.addfile(entry)
                else:
                    if entry.islnk():entry.type=tarfile.REGTYPE;entry.linkname='';entry.size=path.stat().st_size
                    with path.open('rb') as data:archive.addfile(entry,data)
        m={'format':1,'agent':a.agent,'version':a.version,'architecture':'linux-arm64','sha256':hashlib.file_digest(payload.open('rb'),'sha256').hexdigest()}
        with zipfile.ZipFile(a.output,'w',compression=zipfile.ZIP_STORED) as archive:
            archive.writestr('manifest.json',json.dumps(m));archive.write(payload,'payload.tar.gz')
    print(a.output)


if __name__=='__main__':main()
