"""Transactional collaboration documents. Secrets live only in private attempt files."""
import copy
import json
from pathlib import Path
import sqlite3
import threading
import time
import uuid

ACTIVE = {'starting', 'running', 'waiting_input', 'stopping'}
TERMINAL = {'succeeded', 'failed', 'interrupted', 'cancelled', 'needs_reconciliation'}


def ident(): return uuid.uuid4().hex


class CollaborationStore:
    def __init__(self, folder):
        self.folder = Path(folder); self.folder.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.folder/'collaboration.sqlite', check_same_thread=False)
        self.db.execute('PRAGMA journal_mode=WAL'); self.db.execute('PRAGMA synchronous=FULL')
        self.db.execute('PRAGMA secure_delete=ON')
        self.db.executescript('''CREATE TABLE IF NOT EXISTS documents(id TEXT PRIMARY KEY, revision INTEGER NOT NULL,
            request_id TEXT UNIQUE NOT NULL, updated REAL NOT NULL, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS settings(id INTEGER PRIMARY KEY CHECK(id=1),value TEXT NOT NULL);
            PRAGMA user_version=1;''')
        self.db.commit(); (self.folder/'collaboration.sqlite').chmod(0o600)

    def settings(self, value=None):
        with self.lock:
            if value is not None:
                current=self.settings();limit=value.get('parallel',current['parallel'])
                if type(limit) is not int or limit not in (1,2,3):raise ValueError('并行数只能是 1、2 或 3')
                source=value.get('sourceParallel',current.get('sourceParallel',2))
                if type(source) is not int or source not in (1,2,3):raise ValueError('API 来源并发数只能是 1、2 或 3')
                value={'parallel':limit, 'heavy':1,'sourceParallel':source}
                with self.db:self.db.execute('INSERT OR REPLACE INTO settings VALUES(1,?)',(json.dumps(value),))
            row=self.db.execute('SELECT value FROM settings WHERE id=1').fetchone()
            return json.loads(row[0]) if row else {'parallel':2,'heavy':1,'sourceParallel':2}

    def read(self, key):
        with self.lock:
            row=self.db.execute('SELECT value FROM documents WHERE id=?',(key,)).fetchone()
            if row is None:raise ValueError('协作不存在')
            return json.loads(row[0])

    def listing(self, conversation=None):
        with self.lock:docs=[json.loads(r[0]) for r in self.db.execute('SELECT value FROM documents ORDER BY updated DESC')]
        return [d for d in docs if not conversation or d['conversationId']==conversation]

    def create(self, value, request):
        if not isinstance(request,str) or not 1<=len(request)<=200:raise ValueError('缺少创建请求标识')
        with self.lock:
            row=self.db.execute('SELECT value FROM documents WHERE request_id=?',(request,)).fetchone()
            if row:return json.loads(row[0])
            value=copy.deepcopy(value);value.update(id=ident(),revision=1,createdAt=time.time(),updatedAt=time.time())
            with self.db:self.db.execute('INSERT INTO documents VALUES(?,?,?,?,?)',
                (value['id'],1,request,value['updatedAt'],json.dumps(value,ensure_ascii=False)))
            return value

    def by_request(self, request):
        if not isinstance(request,str) or not 1<=len(request)<=200:raise ValueError('缺少创建请求标识')
        with self.lock:
            row=self.db.execute('SELECT value FROM documents WHERE request_id=?',(request,)).fetchone()
            return json.loads(row[0]) if row else None

    def update(self, key, mutate, revision=None):
        with self.lock:
            doc=self.read(key)
            if revision is not None and revision!=doc['revision']:raise ValueError('计划已变更，请刷新后再保存')
            result=mutate(doc);doc['revision']+=1;doc['updatedAt']=time.time()
            with self.db:self.db.execute('UPDATE documents SET revision=?,updated=?,value=? WHERE id=?',
                (doc['revision'],doc['updatedAt'],json.dumps(doc,ensure_ascii=False),key))
            return copy.deepcopy(doc),result

    def close(self):
        with self.lock:self.db.close()

    def delete(self, keys):
        with self.lock:
            with self.db:self.db.executemany('DELETE FROM documents WHERE id=?',[(key,) for key in keys])
            if self.db.execute('PRAGMA wal_checkpoint(TRUNCATE)').fetchone()[0]:
                raise RuntimeError('协作数据库日志仍在使用，清理尚未完成，请稍后重试')
