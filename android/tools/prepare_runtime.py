#!/usr/bin/env python3
"""Prepare a credential-free, single Linux runtime for the standalone probe APK."""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'android/app'
PREFIX = Path(os.environ.get('PREFIX', '/data/data/com.termux/files/usr'))
CACHE = Path.home() / '.cache/aixs-android'
UBUNTU_SHA256 = 'a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2'
UBUNTU_URL = 'https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz'


def prepare():
    os.umask(0o077)
    assets = APP / 'src/main/assets/runtime'
    libs = APP / 'src/main/jniLibs/arm64-v8a'
    assets.mkdir(parents=True, exist_ok=True)
    libs.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    archive = Path.home() / 'ubuntu-arm64.tar.gz'
    if not archive.exists():
        archive = CACHE / 'ubuntu-base-24.04.5-base-arm64.tar.gz'
        if not archive.exists():
            partial = archive.with_suffix('.part')
            with urllib.request.urlopen(UBUNTU_URL, timeout=60) as source, partial.open('wb') as target:
                shutil.copyfileobj(source, target)
            partial.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != UBUNTU_SHA256:
        raise SystemExit('Ubuntu rootfs checksum mismatch')

    # Android disallows creating hard links in app writable directories. Convert
    # them to ordinary files at build time, without adding a second runtime.
    # aapt transparently expands assets ending in .gz; keep the pinned archive
    # byte-for-byte so verification and extraction agree on the installed app.
    output = assets / 'ubuntu-base.bin'
    (assets / 'ubuntu-base.tar.gz').unlink(missing_ok=True)
    with tarfile.open(archive, 'r:gz') as source, tarfile.open(output, 'w:gz', format=tarfile.PAX_FORMAT) as target:
        for entry in source:
            parts = PurePosixPath(entry.name).parts
            if entry.name.startswith('/') or '..' in parts:
                raise SystemExit('Unsafe archive path')
            if not (entry.isfile() or entry.isdir() or entry.issym() or entry.islnk()):
                continue
            if entry.islnk():
                data = source.extractfile(entry).read()
                entry.type = tarfile.REGTYPE
                entry.size = len(data)
                entry.linkname = ''
                body = io.BytesIO(data)
            else:
                body = source.extractfile(entry) if entry.isfile() else None
            entry.uid = entry.gid = 0
            entry.uname = entry.gname = ''
            target.addfile(entry, body)

    files = {
        'libproot.so': 'bin/proot',
        'libloader.so': 'libexec/proot/loader',
        'libloader32.so': 'libexec/proot/loader32',
        'libtalloc.so': 'lib/libtalloc.so.2',
        'libandroid-shmem.so': 'lib/libandroid-shmem.so',
    }
    provenance = []
    for name, relative in files.items():
        data = (PREFIX / relative).read_bytes()
        original = hashlib.sha256(data).hexdigest()
        if name == 'libproot.so':
            old, new = b'libtalloc.so.2\0', b'libtalloc.so\0\0\0'
            if data.count(old) != 1:
                raise SystemExit('Unexpected PRoot dependency layout')
            # Keep ELF section sizes unchanged; package manager extracts *.so.
            data = data.replace(old, new)
        (libs / name).write_bytes(data)
        provenance.append({'file':name,'input':relative,'inputSha256':original,'sha256':hashlib.sha256(data).hexdigest()})
    metadata = {
        'format':1,'architecture':'arm64-v8a','base':'Ubuntu Base 24.04.5',
        'source':UBUNTU_URL,'sourceSha256':UBUNTU_SHA256,
        'archiveSha256':hashlib.sha256(output.read_bytes()).hexdigest(),
        'rootfsCount':1,'nativeFiles':provenance,
    }
    (assets / 'manifest.json').write_text(json.dumps(metadata, indent=2)+'\n')
    print('Prepared one shared runtime; rootfs asset bytes:', output.stat().st_size)


if __name__ == '__main__':
    prepare()
