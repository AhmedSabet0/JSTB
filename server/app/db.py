"""SQLite storage: users, sign-in tokens, reported applications, AI usage, settings and admin sessions."""
import os
import sqlite3
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

DB_PATH = os.environ.get("DB_PATH", "/data/autoapply.db")
TIMEZONE = ZoneInfo(os.environ.get("TIMEZONE", "Africa/Cairo"))

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL DEFAULT '',
  pw_hash TEXT NOT NULL,
  created_at REAL NOT NULL,
  pro INTEGER NOT NULL DEFAULT 0,
  pro_changed_at REAL,
  note TEXT NOT NULL DEFAULT '',
  stop_requested_at REAL,
  running INTEGER NOT NULL DEFAULT 0,
  mode TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT '',
  run_started_at REAL,
  last_seen REAL,
  app_version TEXT NOT NULL DEFAULT '',
  device TEXT NOT NULL DEFAULT '',
  app_total INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS tokens (
  token_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at REAL NOT NULL,
  last_used REAL NOT NULL,
  device TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS tokens_user ON tokens(user_id);
CREATE TABLE IF NOT EXISTS applications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  job_id TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  company TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL,
  score INTEGER,
  at REAL NOT NULL,
  UNIQUE (user_id, job_id, status)
);
CREATE INDEX IF NOT EXISTS applications_user_at ON applications(user_id, at);
CREATE TABLE IF NOT EXISTS ai_usage (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day TEXT NOT NULL,
  calls INTEGER NOT NULL DEFAULT 0,
  tokens INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, day)
);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS admin_sessions (token_hash TEXT PRIMARY KEY, expires_at REAL NOT NULL);
"""

# Settings the admin page changes. Values are stored as text.
DEFAULTS = {
    "openrouter_key": "",
    "model": "openai/gpt-4o-mini",
    "free_ai_per_day": "30",     # enough to read a CV during setup
    "pro_ai_per_day": "800",
    "max_per_day": "0",          # applications per user per day; 0 = the app's own setting
    "signups_open": "1",
    "new_users_pro": "0",
}

_local = threading.local()


def _connect():
    os.makedirs(os.path.dirname(os.path.abspath(DB_PATH)), exist_ok=True)
    c = sqlite3.connect(DB_PATH, timeout=15, isolation_level=None, check_same_thread=False)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA foreign_keys = ON")
    c.execute("PRAGMA busy_timeout = 15000")
    return c


def conn():
    c = getattr(_local, "conn", None)
    if c is None:
        c = _connect()
        _local.conn = c
    return c


@contextmanager
def tx():
    """One write transaction (commits on success, rolls back on error)."""
    c = conn()
    c.execute("BEGIN IMMEDIATE")
    try:
        yield c
    except BaseException:
        c.execute("ROLLBACK")
        raise
    else:
        c.execute("COMMIT")


def init():
    c = conn()
    c.execute("PRAGMA journal_mode = WAL")
    c.executescript(SCHEMA)
    with tx() as t:
        for k, v in DEFAULTS.items():
            t.execute("INSERT OR IGNORE INTO settings(key, value) VALUES (?, ?)", (k, v))
        # First start: take the OpenRouter key and model from the environment, if given.
        for env, key in (("OPENROUTER_KEY", "openrouter_key"), ("OPENROUTER_MODEL", "model")):
            val = os.environ.get(env, "").strip()
            if val:
                row = t.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
                if not row or not row["value"] or row["value"] == DEFAULTS[key]:
                    t.execute("UPDATE settings SET value = ? WHERE key = ?", (val, key))
        t.execute("DELETE FROM admin_sessions WHERE expires_at < ?", (time.time(),))


def one(sql, args=()):
    return conn().execute(sql, args).fetchone()


def all_rows(sql, args=()):
    return conn().execute(sql, args).fetchall()


def settings():
    out = dict(DEFAULTS)
    for r in all_rows("SELECT key, value FROM settings"):
        out[r["key"]] = r["value"]
    return out


def setting_int(s, key, low=0, high=10**6):
    try:
        return max(low, min(high, int(float(s.get(key, DEFAULTS.get(key, "0"))))))
    except (TypeError, ValueError):
        return int(DEFAULTS.get(key, "0"))


# ---------------------------------------------------------------- days in the server's time zone
def today(now=None):
    return datetime.fromtimestamp(now or time.time(), TIMEZONE).strftime("%Y-%m-%d")


def start_of_day(now=None, days_back=0):
    d = datetime.fromtimestamp(now or time.time(), TIMEZONE)
    d = d.replace(hour=0, minute=0, second=0, microsecond=0) - timedelta(days=days_back)
    return d.timestamp()
