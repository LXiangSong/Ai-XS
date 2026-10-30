#!/usr/bin/env python3
"""Run the complete credential-free test suite, lint and signed release build."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
from prepare_gradle import prepare_sdk

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', 'android/tests'], cwd=ROOT, check=True)
    command = [str(ROOT/'android/gradlew'), '--no-daemon', '-PpythonCommand='+sys.executable]
    env = dict(os.environ)
    if args.offline:
        command.append('--offline')
    if '/com.termux/' in sys.executable or env.get('PREFIX', '').endswith('com.termux/files/usr'):
        compiler = shutil.which('aapt2')
        if not compiler:
            raise SystemExit('Install aapt2 first: pkg install aapt2')
        env['ANDROID_HOME'] = str(prepare_sdk())
        env['GRADLE_OPTS'] = env.get('GRADLE_OPTS', '') + ' -Dorg.gradle.native=false -Dorg.gradle.internal.instrumentation.agent=false'
        command.append('-Pandroid.aapt2FromMavenOverride='+compiler)
    command.append(':app:testReleaseUnitTest')
    subprocess.run(command, cwd=ROOT/'android', env=env, check=True)
    build = [sys.executable, str(ROOT/'android/tools/build.py'), '--lint']
    if args.offline:
        build.append('--offline')
    subprocess.run(build, cwd=ROOT, check=True)
    print('All Python tests, Java tests, release lint, build and APK signature checks passed.')


if __name__ == '__main__':
    main()
