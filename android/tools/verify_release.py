#!/usr/bin/env python3
"""Verify APK identity, signature, runtime sources and hashes before publishing."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
from prepare_native_assets import MODULES
from project_metadata import ROOT, version

PRODUCTION_CERT = '0ed3ff23c0a576a8030df21f247ed8de9e89f06216d2732a3d45a8439bcb0c26'


def executable(name, sdk=None):
    found = shutil.which(name)
    if found:
        return found
    sdk = sdk or os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        sdk = Path.home() / '.cache/aixs-android/sdk'
    if sdk:
        candidate = Path(sdk) / 'build-tools/35.0.0' / name
        if candidate.is_file():
            return str(candidate)
    raise ValueError('Required Android build tool not found: ' + name)


def check_badging(badging, metadata):
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    expected = ('app.aixs.mobile', str(metadata['versionCode']), metadata['versionName'])
    if not package or package.groups() != expected:
        raise ValueError('APK package/version does not match source metadata')
    minimum = re.search(r"^(?:sdkVersion|minSdkVersion):'([^']+)'", badging, re.M)
    architecture = re.search(r'^native-code: (.+)$', badging, re.M)
    if not minimum or minimum.group(1) != '26' or not architecture or architecture.group(1).strip() != "'arm64-v8a'":
        raise ValueError('APK must target Android 8+ and ARM64')
    if "targetSdkVersion:'35'" not in badging:
        raise ValueError('Unexpected target Android SDK')


def signing_certificate(output):
    certificates = set(value.lower() for value in re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})', output))
    if len(certificates) != 1:
        raise ValueError('Expected one APK signing identity')
    return certificates.pop()


def verify(apk, root=ROOT, production=False, sdk=None):
    metadata = version(root)
    badging = subprocess.run([executable('aapt2', sdk), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True).stdout
    check_badging(badging, metadata)
    signed = subprocess.run([executable('apksigner', sdk), 'verify', '--print-certs', str(apk)], check=True, capture_output=True, text=True).stdout
    certificate = signing_certificate(signed)
    if production and certificate != PRODUCTION_CERT:
        raise ValueError('APK was not signed with the existing production certificate')
    with zipfile.ZipFile(apk) as archive:
        with zipfile.ZipFile(io.BytesIO(archive.read('assets/runtime/native.zip'))) as native:
            expected_sources = {'agent_core.py': root / 'agent_core.py', 'native_host.py': root / 'android/runtime/native_host.py'}
            expected_sources.update({name: root / name for name in MODULES})
            if set(native.namelist()) != set(expected_sources) | {'version.json'}:
                raise ValueError('Unexpected or missing packaged backend modules')
            for name, source in expected_sources.items():
                if native.read(name) != source.read_bytes():
                    raise ValueError('Packaged backend differs from source: ' + name)
            if json.loads(native.read('version.json')) != metadata:
                raise ValueError('Packaged backend version differs from App version')
        runtime = root / 'android/app/src/main/assets/runtime'
        for name in ('manifest.json', 'ubuntu-base.bin', 'agent_package.py', 'backend_control.py', 'catalog.json'):
            if archive.read('assets/runtime/' + name) != (runtime / name).read_bytes():
                raise ValueError('Packaged runtime asset differs from source: ' + name)
        for source in (root / 'android/app/src/main/jniLibs/arm64-v8a').glob('*.so'):
            if archive.read('lib/arm64-v8a/' + source.name) != source.read_bytes():
                raise ValueError('Packaged native library differs from source: ' + source.name)
    checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
    expected_checksum = f'{checksum}  {apk.name}\n'
    if apk.with_suffix('.apk.sha256').read_text() != expected_checksum:
        raise ValueError('APK checksum file mismatch')
    return dict(metadata, apk=apk.name, apkBytes=apk.stat().st_size, sha256=checksum,
                certificateSha256=certificate, productionSignature=certificate == PRODUCTION_CERT,
                packagedSourcesMatch=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', nargs='?', type=Path)
    parser.add_argument('--production', action='store_true', help='Require the existing App update certificate')
    args = parser.parse_args()
    apk = args.apk or ROOT / 'dist' / ('Ai-XS-' + version()['versionName'] + '.apk')
    report = verify(apk, production=args.production)
    destination = apk.with_suffix('.verification.json')
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
