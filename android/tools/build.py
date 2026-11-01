#!/usr/bin/env python3
"""Run the standard Gradle Android build, then export the signed release APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from prepare_gradle import prepare_sdk
from verify_release import verify

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-probe', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--lint', action='store_true', help='Also run the complete Android lint task')
    parser.add_argument('--offline', action='store_true', help='Build using the already installed SDK and cached dependencies')
    args = parser.parse_args()
    os.umask(0o077)
    env = dict(os.environ)
    termux = '/com.termux/' in sys.executable or bool(env.get('PREFIX', '').endswith('com.termux/files/usr'))
    command = [str(ROOT / 'android/gradlew'), '--no-daemon', '-PpythonCommand=' + sys.executable]
    if args.offline:
        command.append('--offline')
    resource_compiler = shutil.which('aapt2')
    if not env.get('ANDROID_HOME') and not env.get('ANDROID_SDK_ROOT'):
        env['ANDROID_HOME'] = str(prepare_sdk())
    if termux and not resource_compiler:
        raise SystemExit('Install the ARM64 resource compiler with: pkg install aapt2')
    if termux:
        env['GRADLE_OPTS'] = env.get('GRADLE_OPTS', '') + ' -Dorg.gradle.native=false -Dorg.gradle.internal.instrumentation.agent=false'
    if resource_compiler:
        command += ['-Pandroid.aapt2FromMavenOverride=' + resource_compiler]
    command += [':app:assembleRelease']
    if args.lint:
        command += [':app:lintRelease']
    subprocess.run(command, cwd=ROOT / 'android', env=env, check=True)
    output = ROOT / 'android/app/build/outputs/apk/release'
    metadata = json.loads((output / 'output-metadata.json').read_text())
    release = metadata['elements'][0]
    source = output / release['outputFile']
    destination = ROOT / 'dist' / ('Ai-XS-' + release['versionName'] + '.apk')
    destination.parent.mkdir(exist_ok=True)
    shutil.copyfile(source, destination)
    checksum = hashlib.sha256(destination.read_bytes()).hexdigest()
    destination.with_suffix('.apk.sha256').write_text(checksum + '  ' + destination.name + '\n')
    verifier = shutil.which('apksigner')
    if not verifier:
        sdk = env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT')
        candidate = Path(sdk) / 'build-tools/35.0.0/apksigner' if sdk else None
        if candidate and candidate.is_file():
            verifier = str(candidate)
    if not verifier:
        raise SystemExit('Android apksigner is required to verify the release APK')
    subprocess.run([verifier, 'verify', str(destination)], check=True)
    report = verify(destination, sdk=env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT'))
    destination.with_suffix('.verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Gradle release:', destination)
    print('SHA256:', checksum)


if __name__ == '__main__':
    main()
