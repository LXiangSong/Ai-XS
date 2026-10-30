"""Explicit per-session approval choices for Ai-XS's Android runtime."""
import json
from pathlib import Path
import threading

MODES = ('ask', 'auto')


def validate(mode):
    if mode not in MODES:
        raise ValueError('权限模式无效，请选择询问权限或自动执行')
    return mode


def apply(params, mode, method, engine):
    result = dict(params)
    policy = 'never' if validate(mode) == 'auto' else 'on-request'
    if engine == 'codex':
        result['approvalPolicy'] = policy
        if method == 'turn/start':
            result['sandboxPolicy'] = {'type': 'dangerFullAccess'}
        else:
            result['sandbox'] = 'danger-full-access'
    else:
        result.update(permissionMode=mode, approvalPolicy=policy, sandbox='danger-full-access')
    return result


class PermissionModes:
    def __init__(self, folder):
        self.path = Path(folder) / 'permission-modes.json'
        self.lock = threading.RLock()

    def read(self):
        try:
            return json.loads(self.path.read_text())
        except FileNotFoundError:
            return {}

    def mode(self, engine, thread=''):
        with self.lock:
            values = self.read()
            return validate(values.get('thread:' + thread, values.get(engine, 'ask')))

    def set(self, engine, mode, thread=''):
        validate(mode)
        with self.lock:
            values = self.read()
            values['thread:' + thread if thread else engine] = mode
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.path.with_suffix('.tmp')
            temporary.write_text(json.dumps(values))
            temporary.chmod(0o600)
            temporary.replace(self.path)
        return {'mode': mode}
