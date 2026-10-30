#!/usr/bin/env python3
"""Build a portable official Agent bundle on ARM64 glibc Linux (e.g. Ubuntu)."""
import argparse
import platform
from pathlib import Path
import subprocess
import sys
import tempfile

ANDROID=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ANDROID/'runtime'))
from agent_package import AGENTS,populate_prefix,validate


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('agent',choices=AGENTS)
    parser.add_argument('version')
    parser.add_argument('output',type=Path)
    args=parser.parse_args();validate(args.agent,args.version)
    if platform.machine() not in ('aarch64','arm64') or platform.libc_ver()[0]!='glibc':
        raise SystemExit('Run inside ARM64 Ubuntu/glibc Linux, not Android Termux or x86 Linux')
    output=args.output.resolve();output.parent.mkdir(parents=True,exist_ok=True)
    if output.exists():raise SystemExit('Output already exists; use a new filename')
    with tempfile.TemporaryDirectory(prefix='aixs-agent-') as folder:
        prefix=Path(folder)
        populate_prefix(prefix,args.agent,args.version)
        subprocess.run([sys.executable,str(ANDROID/'tools/make_agent_bundle.py'),args.agent,args.version,str(prefix),str(output)],check=True)


if __name__=='__main__':main()
