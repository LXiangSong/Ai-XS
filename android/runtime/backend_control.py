"""Stop only Ai-XS's own internal backend, including a previous app process orphan."""
import os
from pathlib import Path
import signal
import time

TARGET=b'/opt/aixs/web/server.py'
TARGETS={TARGET,b'/opt/aixs/native/native_host.py'}


def matches(pid):
    if pid==os.getpid():return False
    try:
        parts=(Path('/proc')/str(pid)/'cmdline').read_bytes().split(b'\0')
        return any(target in parts for target in TARGETS) and b'python' in Path(os.fsdecode(parts[0])).name.encode()
    except (OSError,ValueError):return False


def stop():
    targets=[]
    for path in Path('/proc').iterdir():
        if path.name.isdigit() and matches(int(path.name)):
            pid=int(path.name)
            try:os.kill(pid,signal.SIGTERM);targets.append(pid)
            except (ProcessLookupError,PermissionError):pass
    deadline=time.monotonic()+12
    while time.monotonic()<deadline and any(matches(pid) for pid in targets):time.sleep(.15)
    for pid in targets:
        if matches(pid):
            try:os.kill(pid,signal.SIGKILL)
            except (ProcessLookupError,PermissionError):pass
    print('已清理 Ai-XS 服务进程：'+str(len(targets)),flush=True)


if __name__=='__main__':stop()
