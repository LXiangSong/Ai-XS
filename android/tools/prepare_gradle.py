#!/usr/bin/env python3
"""Prepare the official Android SDK for a Termux-hosted Gradle build."""
import hashlib
import os
from pathlib import Path
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

CACHE = Path.home() / '.cache/aixs-android'
REPOSITORY = 'https://dl.google.com/android/repository/'


def prepare_sdk():
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or CACHE / 'sdk')
    required = [sdk / 'platforms/android-35/android.jar', sdk / 'build-tools/35.0.0/source.properties']
    if all(path.is_file() for path in required):
        return sdk
    CACHE.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(REPOSITORY + 'repository2-1.xml', timeout=60) as response:
        document = ET.fromstring(response.read())
    for package_id, directory in [('platforms;android-35', 'platforms/android-35'), ('build-tools;35.0.0', 'build-tools/35.0.0')]:
        target = sdk / directory
        if (target / 'source.properties').is_file():
            continue
        package = next(p for p in document if p.tag.endswith('remotePackage') and p.get('path') == package_id)
        candidates = list(package.find('archives'))
        archive = next(a for a in candidates if a.findtext('host-os') in (None, 'linux'))
        complete = archive.find('complete')
        name = complete.findtext('url')
        expected = complete.findtext('checksum')
        source = CACHE / name
        if not source.is_file():
            part = source.with_suffix('.part')
            with urllib.request.urlopen(REPOSITORY + name, timeout=60) as response, part.open('wb') as output:
                shutil.copyfileobj(response, output)
            part.replace(source)
        if hashlib.sha1(source.read_bytes()).hexdigest() != expected:
            raise ValueError('Official SDK archive checksum mismatch: ' + name)
        with zipfile.ZipFile(source) as content:
            prefix = next(n.rsplit('/', 1)[0] + '/' for n in content.namelist() if n.endswith('/source.properties'))
            for entry in content.infolist():
                if entry.is_dir() or not entry.filename.startswith(prefix):
                    continue
                relative = Path(entry.filename[len(prefix):])
                if relative.is_absolute() or '..' in relative.parts:
                    raise ValueError('Unsafe SDK archive entry')
                destination = target / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(content.read(entry))
                if directory.startswith('build-tools'):
                    destination.chmod(0o700)
        if package_id == 'platforms;android-35':
            # Google's base-platform repository entry currently serves an extension
            # archive; describe it as the base API selected by that repository entry.
            properties = target / 'source.properties'
            text = properties.read_text().replace('AndroidVersion.IsBaseSdk=false', 'AndroidVersion.IsBaseSdk=true')
            lines = ['AndroidVersion.ExtensionLevel=0' if line.startswith('AndroidVersion.ExtensionLevel=') else line for line in text.splitlines()]
            properties.write_text('\n'.join(lines) + '\n')
        print('Official SDK archive verified:', package_id, flush=True)
    return sdk


if __name__ == '__main__':
    print(prepare_sdk())
