#!/usr/bin/env python3
"""Run the complete credential-free test suite, lint and signed release build."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from prepare_gradle import prepare_sdk
from check_project import check
from project_metadata import version

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    check()
    tests = subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', 'android/tests'], cwd=ROOT, capture_output=True, text=True)
    print(tests.stdout, end='', flush=True)
    print(tests.stderr, end='', file=sys.stderr, flush=True)
    tests.check_returncode()
    python_count = int(re.search(r'Ran (\d+) tests', tests.stderr).group(1))
    command = [str(ROOT/'android/gradlew'), '--no-daemon', '-PpythonCommand='+sys.executable]
    env = dict(os.environ)
    if args.offline:
        command.append('--offline')
    compiler = shutil.which('aapt2')
    if not env.get('ANDROID_HOME') and not env.get('ANDROID_SDK_ROOT'):
        env['ANDROID_HOME'] = str(prepare_sdk())
    if '/com.termux/' in sys.executable or env.get('PREFIX', '').endswith('com.termux/files/usr'):
        if not compiler:
            raise SystemExit('Install aapt2 first: pkg install aapt2')
        env['GRADLE_OPTS'] = env.get('GRADLE_OPTS', '') + ' -Dorg.gradle.native=false -Dorg.gradle.internal.instrumentation.agent=false'
    if compiler:
        command.append('-Pandroid.aapt2FromMavenOverride='+compiler)
    command.append(':app:testReleaseUnitTest')
    subprocess.run(command, cwd=ROOT/'android', env=env, check=True)
    build = [sys.executable, str(ROOT/'android/tools/build.py'), '--lint']
    if args.offline:
        build.append('--offline')
    subprocess.run(build, cwd=ROOT, check=True)
    rows = [ET.parse(path).getroot() for path in (ROOT/'android/app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml')]
    counts = {key: sum(int(row.get(key, '0')) for row in rows) for key in ('tests', 'failures', 'errors', 'skipped')}
    if not counts['tests'] or counts['failures'] or counts['errors'] or counts['skipped']:
        raise ValueError('Java test reports contain failures or skipped tests')
    issues = ET.parse(ROOT/'android/app/build/reports/lint-results-release.xml').getroot().findall('issue')
    lint_errors = sum(issue.get('severity') in ('Error', 'Fatal') for issue in issues)
    if lint_errors:
        raise ValueError('Release Lint contains errors')
    report = dict(version(), pythonTests=python_count, java=counts, lintErrors=lint_errors,
                  lintWarnings=sum(issue.get('severity') == 'Warning' for issue in issues),
                  phoneUIAndRealModelsTested=False)
    (ROOT/'dist'/('Ai-XS-'+version()['versionName']+'.validation.json')).write_text(json.dumps(report, indent=2)+'\n')
    print('All Python tests, Java tests, release lint, build and APK signature checks passed.')


if __name__ == '__main__':
    main()
