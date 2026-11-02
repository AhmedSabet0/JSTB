"""Auto Apply server.

- Accounts for the app (email + password).
- The AI proxy: the app sends the conversation, the server adds the OpenRouter key and model set in the admin page,
  so the key never reaches a phone.
- Reports: while it works, the app reports its status and the jobs it applied to. The answer can tell it to stop.
- The admin page (/admin): every user, Pro on/off, applications today / this week / in total, Stop and Stop all,
  and the AI settings.
- Public pages Google Play asks for: /privacy and /delete-account.
"""
import hmac
import logging
import os
import re
import sqlite3
import time
from contextlib import asynccontextmanager
from urllib.parse import parse_qs

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

from . import ai, db, pages, security

log = logging.getLogger("autoapply")

ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "")
APP_NAME = os.environ.get("APP_NAME", "Auto Apply")
CONTACT = os.environ.get("CONTACT_EMAIL", "")
STATIC = os.path.join(os.path.dirname(__file__), "static")
STATIC_FILES = {"admin.css", "admin.js", "figtree.woff2"}

REPORT_EVERY = 30        # seconds between reports while auto apply runs
ONLINE_FOR = 150         # a running app that reported this recently counts as running now
KEPT_STATUSES = ("applied", "error", "stuck", "needs_answer")
CHANNELS = ("auto", "linkedin", "company", "email")
STAGES = ("", "applied", "interview", "offer", "rejected")
EVENTS_KEPT = 3000       # activity lines kept per user
ADMIN_HOURS = 12

auth_limit = security.RateLimit(12, 600)     # sign-in / sign-up attempts per IP
email_limit = security.RateLimit(8, 900)     # sign-in attempts per email
admin_limit = security.RateLimit(8, 900)     # admin sign-in attempts per IP
delete_limit = security.RateLimit(6, 600)    # web account deletion attempts per IP


class ApiError(Exception):
    def __init__(self, status, message, code="error"):
        super().__init__(message)
        self.status = status
        self.message = message
        self.code = code


@asynccontextmanager
async def lifespan(_app):
    db.init()
    if not ADMIN_PASSWORD:
        log.warning("ADMIN_PASSWORD is not set: the admin page is disabled.")
    yield
    await ai.close()


app = FastAPI(title="Auto Apply server", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)


@app.exception_handler(ApiError)
async def on_api_error(_request, exc):
    return JSONResponse({"ok": False, "error": {"message": exc.message, "code": exc.code}}, status_code=exc.status)


ADMIN_CSP = ("default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; "
             "font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")


@app.middleware("http")
async def security_headers(request, call_next):
    resp = await call_next(request)
    resp.headers.setdefault("X-Content-Type-Options", "nosniff")
    resp.headers.setdefault("Referrer-Policy", "no-referrer")
    resp.headers.setdefault("X-Frame-Options", "DENY")
    if request.url.path.startswith("/admin") or request.url.path.startswith("/v1/"):
        resp.headers["Cache-Control"] = "no-store"
    if request.url.path.startswith("/admin"):
        resp.headers["Content-Security-Policy"] = ADMIN_CSP
    return resp


# ---------------------------------------------------------------- helpers
def client_ip(request):
    return request.client.host if request.client else "?"


async def body_json(request):
    try:
        data = await request.json()
    except ValueError:
        raise ApiError(400, "Send a JSON body.", "bad_request")
    if not isinstance(data, dict):
        raise ApiError(400, "Send a JSON object.", "bad_request")
    return data


def text(value, limit):
    return str(value if value is not None else "").strip()[:limit]


def number(value, low, high, default):
    try:
        return max(low, min(high, float(value)))
    except (TypeError, ValueError):
        return default


def current_user(request):
    """The signed-in user of the app (Authorization: Bearer <token>)."""
    h = request.headers.get("authorization", "")
    if not h.lower().startswith("bearer ") or len(h) < 20:
        raise ApiError(401, "Sign in to your account first.", "signed_out")
    th = security.token_hash(h[7:].strip())
    row = db.one("SELECT u.* FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ?", (th,))
    if row is None:
        raise ApiError(401, "You were signed out of your account. Sign in again.", "signed_out")
    now = time.time()
    db.conn().execute("UPDATE tokens SET last_used = ? WHERE token_hash = ? AND last_used < ?", (now, th, now - 60))
    return row


def ai_limit(user, s):
    return db.setting_int(s, "pro_ai_per_day" if user["pro"] else "free_ai_per_day")


def ai_used(user_id, day):
    r = db.one("SELECT calls FROM ai_usage WHERE user_id = ? AND day = ?", (user_id, day))
    return r["calls"] if r else 0


def app_info(request):
    """(flavor, versionCode) the app sends in its headers."""
    flavor = text(request.headers.get("x-app-flavor"), 10).lower()
    try:
        code = int(request.headers.get("x-app-version", "0"))
    except ValueError:
        code = 0
    return (flavor if flavor in ("github", "play") else ""), code


def limits(user, s):
    """Applications a day / an hour for this user: the user's own cap from the admin, else the global one (0 = none)."""
    day = user["max_per_day"] if user["max_per_day"] is not None else db.setting_int(s, "max_per_day")
    hour = user["max_per_hour"] if user["max_per_hour"] is not None else db.setting_int(s, "max_per_hour")
    return int(day or 0), int(hour or 0)


def update_info(flavor, code, s):
    need = db.setting_int(s, "min_version_play" if flavor == "play" else "min_version_github", 0, 10 ** 7)
    return {"required": bool(code and need and code < need), "min_version": need, "play_url": s.get("play_url", "")}


def me_json(user, request=None):
    s = db.settings()
    day, hour = limits(user, s)
    flavor, code = app_info(request) if request is not None else ("", 0)
    return {"id": user["id"], "email": user["email"], "name": user["name"], "pro": bool(user["pro"]),
            "max_per_day": day, "max_per_hour": hour, "ai_per_day": ai_limit(user, s),
            "ai_used_today": ai_used(user["id"], db.today()), "report_every": REPORT_EVERY,
            "announcement": s.get("announcement", ""), "update": update_info(flavor, code, s)}


def new_session(t, user_id, device):
    token = security.new_token()
    now = time.time()
    t.execute("INSERT INTO tokens(token_hash, user_id, created_at, last_used, device) VALUES (?, ?, ?, ?, ?)",
              (security.token_hash(token), user_id, now, now, device))
    return token


# ---------------------------------------------------------------- public pages
@app.get("/", response_class=HTMLResponse)
async def home():
    return pages.home()


@app.get("/privacy", response_class=HTMLResponse)
async def privacy():
    return pages.privacy()


@app.get("/delete-account", response_class=HTMLResponse)
async def delete_page():
    return pages.delete_form()


@app.post("/delete-account", response_class=HTMLResponse)
async def delete_web(request: Request):
    if not delete_limit.allow(client_ip(request)):
        return HTMLResponse(pages.delete_form("Too many attempts. Wait 10 minutes and try again."), status_code=429)
    form = parse_qs((await request.body()).decode("utf-8", "replace"))
    email = security.clean_email((form.get("email") or [""])[0])
    password = (form.get("password") or [""])[0]
    row = db.one("SELECT id, pw_hash FROM users WHERE email = ?", (email,)) if email else None
    ok = await run_in_threadpool(security.check_password, password, row["pw_hash"] if row else None)
    if row is None or not ok:
        return HTMLResponse(pages.delete_form("Wrong email or password."), status_code=400)
    with db.tx() as t:
        t.execute("DELETE FROM users WHERE id = ?", (row["id"],))
    return HTMLResponse(pages.delete_form("Your account and its data were deleted.", ok=True))


@app.get("/health")
async def health():
    return {"ok": True}


@app.get("/v1/config")
async def public_config():
    s = db.settings()
    return {"ok": True, "app_name": APP_NAME, "contact_email": CONTACT, "signups_open": s.get("signups_open") == "1"}


# ---------------------------------------------------------------- accounts
@app.post("/v1/auth/signup")
async def signup(request: Request):
    b = await body_json(request)
    if not auth_limit.allow(client_ip(request)):
        raise ApiError(429, "Too many attempts. Wait a few minutes and try again.", "rate_limited")
    s = db.settings()
    if s.get("signups_open") != "1":
        raise ApiError(403, "New accounts are closed right now.", "signups_closed")
    email = security.clean_email(b.get("email"))
    if not email:
        raise ApiError(400, "Type a valid email address.", "bad_email")
    password = str(b.get("password") or "")
    problem = security.password_problem(password)
    if problem:
        raise ApiError(400, problem, "bad_password")
    pw_hash = await run_in_threadpool(security.hash_password, password)
    pro = 1 if s.get("new_users_pro") == "1" else 0
    now = time.time()
    try:
        with db.tx() as t:
            cur = t.execute("INSERT INTO users(email, name, pw_hash, created_at, pro, pro_changed_at) VALUES (?, ?, ?, ?, ?, ?)",
                            (email, text(b.get("name"), 80), pw_hash, now, pro, now if pro else None))
            uid = cur.lastrowid
            token = new_session(t, uid, text(b.get("device"), 80))
    except sqlite3.IntegrityError:
        raise ApiError(409, "An account with this email already exists. Sign in instead.", "email_taken")
    return {"ok": True, "token": token, "user": me_json(db.one("SELECT * FROM users WHERE id = ?", (uid,)), request)}


@app.post("/v1/auth/login")
async def login(request: Request):
    b = await body_json(request)
    email = security.clean_email(b.get("email"))
    if not auth_limit.allow(client_ip(request)) or (email and not email_limit.allow(email)):
        raise ApiError(429, "Too many attempts. Wait a few minutes and try again.", "rate_limited")
    row = db.one("SELECT * FROM users WHERE email = ?", (email,)) if email else None
    ok = await run_in_threadpool(security.check_password, str(b.get("password") or ""), row["pw_hash"] if row else None)
    if row is None or not ok:
        raise ApiError(401, "Wrong email or password.", "bad_login")
    with db.tx() as t:
        token = new_session(t, row["id"], text(b.get("device"), 80))
    return {"ok": True, "token": token, "user": me_json(row, request)}


@app.post("/v1/auth/logout")
async def logout(request: Request):
    h = request.headers.get("authorization", "")
    if h.lower().startswith("bearer "):
        with db.tx() as t:
            t.execute("DELETE FROM tokens WHERE token_hash = ?", (security.token_hash(h[7:].strip()),))
    return {"ok": True}


@app.get("/v1/me")
async def me(request: Request):
    return {"ok": True, "user": me_json(current_user(request), request)}


@app.post("/v1/me/delete")
async def delete_me(request: Request):
    user = current_user(request)
    b = await body_json(request)
    if not await run_in_threadpool(security.check_password, str(b.get("password") or ""), user["pw_hash"]):
        raise ApiError(400, "That password is not right.", "bad_password")
    with db.tx() as t:
        t.execute("DELETE FROM users WHERE id = ?", (user["id"],))
    return {"ok": True}


# ---------------------------------------------------------------- the AI proxy
@app.post("/v1/ai/chat")
async def ai_chat(request: Request):
    user = current_user(request)
    b = await body_json(request)
    messages = ai.clean_messages(b.get("messages"))
    if messages is None:
        raise ApiError(400, "This AI request is not valid (empty or too long).", "bad_request")
    purpose = text(b.get("purpose"), 20) or "apply"
    s = db.settings()
    if not user["pro"] and purpose not in ("setup", "check"):
        flavor, _ = app_info(request)
        raise ApiError(403, "AI help isn't active on your account yet." if flavor == "play"
                       else "Auto apply isn't active on your account yet.", "not_active")
    key = s.get("openrouter_key", "")
    if not key:
        raise ApiError(403, "The AI service is not set up yet. (Admin: add the OpenRouter key in the admin page.)",
                       "ai_not_configured")
    limit = ai_limit(user, s)
    day = db.today()
    with db.tx() as t:
        t.execute("INSERT INTO ai_usage(user_id, day, calls, tokens) VALUES (?, ?, 0, 0) ON CONFLICT(user_id, day) DO NOTHING",
                  (user["id"], day))
        used = t.execute("SELECT calls FROM ai_usage WHERE user_id = ? AND day = ?", (user["id"], day)).fetchone()["calls"]
        if used >= limit:
            raise ApiError(403, f"Your AI limit for today ({limit} requests) is used up. It starts again tomorrow.",
                           "ai_limit")
        t.execute("UPDATE ai_usage SET calls = calls + 1 WHERE user_id = ? AND day = ?", (user["id"], day))
    max_tokens = int(number(b.get("max_tokens"), 1, ai.MAX_TOKENS, 600))
    temperature = number(b.get("temperature"), 0, 1.5, 0.2)
    try:
        data = await ai.chat(key, s.get("model") or db.DEFAULTS["model"], messages, max_tokens, temperature)
    except ai.Upstream as e:
        raise ApiError(e.status, e.message, e.code)
    usage = data.get("usage") if isinstance(data.get("usage"), dict) else {}
    tokens = int(number(usage.get("total_tokens"), 0, 10 ** 7, 0))
    if tokens:
        db.conn().execute("UPDATE ai_usage SET tokens = tokens + ? WHERE user_id = ? AND day = ?", (tokens, user["id"], day))
    return {"id": data.get("id"), "model": data.get("model"), "choices": data.get("choices"), "usage": usage}


# ---------------------------------------------------------------- reports from the app
@app.post("/v1/report")
async def report(request: Request):
    user = current_user(request)
    b = await body_json(request)
    now = time.time()
    running = bool(b.get("running"))
    events = b.get("events") if isinstance(b.get("events"), list) else []
    lines = b.get("log") if isinstance(b.get("log"), list) else []
    flavor, code = app_info(request)
    s = db.settings()
    stop, reason = False, ""
    with db.tx() as t:
        row = t.execute("SELECT * FROM users WHERE id = ?", (user["id"],)).fetchone()
        stop_at = row["stop_requested_at"]
        run_started = row["run_started_at"]
        if b.get("start"):
            run_started = now
            stop_at = None          # a stop pressed before this run started was meant for an earlier run
        if running and stop_at:
            stop, reason, stop_at = True, "Stopped by the admin.", None
        elif running and not row["pro"]:
            stop, reason = True, "Auto apply was turned off for your account."
        if not running:
            stop_at = None
        t.execute("UPDATE users SET running = ?, mode = ?, status = ?, last_seen = ?, run_started_at = ?, "
                  "stop_requested_at = ?, app_version = ?, device = ?, app_total = ?, flavor = ?, version_code = ? "
                  "WHERE id = ?",
                  (1 if running else 0, text(b.get("mode"), 10) or row["mode"], text(b.get("message"), 200), now,
                   run_started, stop_at, text(b.get("app_version"), 20) or row["app_version"],
                   text(b.get("device"), 80) or row["device"],
                   int(number(b.get("applied_total"), 0, 10 ** 7, row["app_total"])), flavor or row["flavor"],
                   code or row["version_code"], user["id"]))
        for ev in events[:300]:
            if not isinstance(ev, dict) or ev.get("status") not in KEPT_STATUSES:
                continue
            job_id = text(ev.get("job_id"), 80)
            if not job_id:
                continue
            at = number(ev.get("at"), 0, now + 600, now)
            if abs(at - now) > 86400 * 3:   # the phone's clock is off: use the server's
                at = now
            score = ev.get("score")
            score = int(score) if isinstance(score, (int, float)) and 0 <= score <= 10 else None
            channel = ev.get("channel") if ev.get("channel") in CHANNELS else "auto"
            stage = ev.get("stage") if ev.get("stage") in STAGES else ""
            t.execute("INSERT INTO applications(user_id, job_id, title, company, status, score, at, channel, stage, reason, url) "
                      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user_id, job_id, status) DO UPDATE SET "
                      "stage = CASE WHEN excluded.stage != '' THEN excluded.stage ELSE applications.stage END, "
                      "reason = CASE WHEN excluded.reason != '' THEN excluded.reason ELSE applications.reason END",
                      (user["id"], job_id, text(ev.get("title"), 200), text(ev.get("company"), 120), ev["status"], score, at,
                       channel, stage, text(ev.get("reason"), 300), text(ev.get("url"), 500)))
        kept = 0
        for line in lines[-400:]:
            if isinstance(line, dict) and text(line.get("text"), 600):
                at = number(line.get("at"), 0, now + 600, now)
                t.execute("INSERT INTO events(user_id, at, text) VALUES (?, ?, ?)",
                          (user["id"], at if abs(at - now) < 86400 * 3 else now, text(line.get("text"), 600)))
                kept += 1
        if kept:
            t.execute("DELETE FROM events WHERE user_id = ? AND id <= (SELECT id FROM events WHERE user_id = ? "
                      "ORDER BY id DESC LIMIT 1 OFFSET ?)", (user["id"], user["id"], EVENTS_KEPT))
    day, hour = limits(row, s)
    return {"ok": True, "stop": stop, "reason": reason, "pro": bool(row["pro"]), "max_per_day": day, "max_per_hour": hour,
            "announcement": s.get("announcement", ""), "update": update_info(flavor, code, s), "every": REPORT_EVERY}


# ---------------------------------------------------------------- admin
def require_admin(request, write=False):
    tok = request.cookies.get("aa_admin", "")
    row = db.one("SELECT expires_at FROM admin_sessions WHERE token_hash = ?", (security.token_hash(tok),)) if tok else None
    if row is None or row["expires_at"] < time.time():
        raise ApiError(401, "Sign in to the admin page.", "admin_signed_out")
    if write and request.headers.get("x-requested-with") != "admin":
        raise ApiError(403, "This request did not come from the admin page.", "csrf")


@app.get("/admin")
async def admin_page():
    return FileResponse(os.path.join(STATIC, "admin.html"), media_type="text/html")


@app.get("/admin/static/{name}")
async def admin_static(name: str):
    if name not in STATIC_FILES:
        raise ApiError(404, "Not found.", "not_found")
    return FileResponse(os.path.join(STATIC, name))


@app.post("/admin/api/login")
async def admin_login(request: Request):
    if not ADMIN_PASSWORD:
        raise ApiError(503, "Set ADMIN_PASSWORD in the server's .env file, then restart the server.", "admin_disabled")
    if not admin_limit.allow(client_ip(request)):
        raise ApiError(429, "Too many attempts. Wait 15 minutes and try again.", "rate_limited")
    b = await body_json(request)
    if not hmac.compare_digest(str(b.get("password") or "").encode(), ADMIN_PASSWORD.encode()):
        raise ApiError(401, "Wrong password.", "bad_login")
    tok = security.new_token()
    now = time.time()
    with db.tx() as t:
        t.execute("DELETE FROM admin_sessions WHERE expires_at < ?", (now,))
        t.execute("INSERT INTO admin_sessions(token_hash, expires_at) VALUES (?, ?)", (security.token_hash(tok), now + ADMIN_HOURS * 3600))
    resp = JSONResponse({"ok": True})
    resp.set_cookie("aa_admin", tok, max_age=ADMIN_HOURS * 3600, httponly=True, secure=request.url.scheme == "https",
                    samesite="strict", path="/admin")
    return resp


@app.post("/admin/api/logout")
async def admin_logout(request: Request):
    tok = request.cookies.get("aa_admin", "")
    if tok:
        with db.tx() as t:
            t.execute("DELETE FROM admin_sessions WHERE token_hash = ?", (security.token_hash(tok),))
    resp = JSONResponse({"ok": True})
    resp.delete_cookie("aa_admin", path="/admin")
    return resp


def settings_json():
    s = db.settings()
    key = s.get("openrouter_key", "")
    return {"has_key": bool(key), "key_hint": (key[:9] + "…" + key[-4:]) if len(key) > 16 else ("set" if key else ""),
            "model": s.get("model", ""), "free_ai_per_day": db.setting_int(s, "free_ai_per_day"),
            "pro_ai_per_day": db.setting_int(s, "pro_ai_per_day"), "max_per_day": db.setting_int(s, "max_per_day"),
            "max_per_hour": db.setting_int(s, "max_per_hour"),
            "signups_open": s.get("signups_open") == "1", "new_users_pro": s.get("new_users_pro") == "1",
            "announcement": s.get("announcement", ""), "play_url": s.get("play_url", ""),
            "min_version_github": db.setting_int(s, "min_version_github", 0, 10 ** 7),
            "min_version_play": db.setting_int(s, "min_version_play", 0, 10 ** 7)}


USER_SQL = """
SELECT u.id, u.email, u.name, u.created_at, u.pro, u.pro_changed_at, u.note, u.running, u.mode, u.status, u.last_seen,
  u.app_version, u.device, u.app_total, u.stop_requested_at, u.run_started_at, u.max_per_day, u.max_per_hour,
  u.flavor, u.version_code,
  (SELECT e.text FROM events e WHERE e.user_id = u.id ORDER BY e.id DESC LIMIT 1) AS last_event,
  (SELECT COUNT(*) FROM applications a WHERE a.user_id = u.id AND a.status = 'applied' AND a.channel != 'auto'
     AND a.at >= :day0) AS helped_today,
  (SELECT COUNT(*) FROM applications a WHERE a.user_id = u.id AND a.status = 'applied' AND a.at >= :day0) AS applied_today,
  (SELECT COUNT(*) FROM applications a WHERE a.user_id = u.id AND a.status = 'applied' AND a.at >= :week0) AS applied_week,
  (SELECT COUNT(*) FROM applications a WHERE a.user_id = u.id AND a.status = 'applied') AS applied_total,
  (SELECT MAX(a.at) FROM applications a WHERE a.user_id = u.id AND a.status = 'applied') AS last_applied_at,
  (SELECT COUNT(*) FROM applications a WHERE a.user_id = u.id AND a.status IN ('error', 'stuck') AND a.at >= :day0)
    AS problems_today,
  (SELECT COALESCE(SUM(g.calls), 0) FROM ai_usage g WHERE g.user_id = u.id AND g.day = :today) AS ai_today,
  (SELECT COALESCE(SUM(g.tokens), 0) FROM ai_usage g WHERE g.user_id = u.id AND g.day = :today) AS tokens_today,
  (SELECT COUNT(*) FROM tokens k WHERE k.user_id = u.id) AS devices
FROM users u
"""


def user_json(r, now):
    d = dict(r)
    d["pro"] = bool(d["pro"])
    d["online"] = bool(d["running"]) and d["last_seen"] is not None and now - d["last_seen"] < ONLINE_FOR
    d["state"] = "running" if d["online"] else ("lost" if d["running"] else "idle")
    d["stop_pending"] = d.pop("stop_requested_at") is not None
    return d


def sql_args(now):
    return {"day0": db.start_of_day(now), "week0": db.start_of_day(now, 6), "today": db.today(now)}


@app.get("/admin/api/overview")
async def admin_overview(request: Request):
    require_admin(request)
    now = time.time()
    users = [user_json(r, now) for r in db.all_rows(USER_SQL + " ORDER BY u.created_at DESC", sql_args(now))]
    totals = {"users": len(users), "pro": sum(1 for u in users if u["pro"]),
              "running": sum(1 for u in users if u["online"]),
              "applied_today": sum(u["applied_today"] for u in users),
              "applied_week": sum(u["applied_week"] for u in users),
              "applied_total": sum(u["applied_total"] for u in users),
              "ai_today": sum(u["ai_today"] for u in users), "tokens_today": sum(u["tokens_today"] for u in users)}
    versions = {}
    for u in users:
        if u["app_version"]:
            k = (u["flavor"] or "?", u["app_version"], u["version_code"] or 0)
            versions[k] = versions.get(k, 0) + 1
    totals["versions"] = [{"flavor": k[0], "version": k[1], "code": k[2], "users": n}
                          for k, n in sorted(versions.items(), key=lambda kv: -kv[0][2])]
    return {"ok": True, "now": now, "users": users, "totals": totals, "settings": settings_json()}


def get_user(uid):
    now = time.time()
    r = db.one(USER_SQL + " WHERE u.id = :uid", dict(sql_args(now), uid=uid))
    if r is None:
        raise ApiError(404, "This user is not there any more.", "no_user")
    return user_json(r, now)


@app.get("/admin/api/users/{uid}")
async def admin_user(uid: int, request: Request):
    require_admin(request)
    user = get_user(uid)
    apps = [dict(r) for r in db.all_rows(
        "SELECT job_id, title, company, status, score, at, channel, stage, reason, url FROM applications WHERE user_id = ? "
        "ORDER BY at DESC LIMIT 200", (uid,))]
    for a in apps:
        if not a["url"] and a["job_id"].isdigit():
            a["url"] = f"https://www.linkedin.com/jobs/view/{a['job_id']}/"
    days = [dict(r) for r in db.all_rows(
        "SELECT day, calls, tokens FROM ai_usage WHERE user_id = ? ORDER BY day DESC LIMIT 14", (uid,))]
    return {"ok": True, "user": user, "applications": apps, "ai_days": days}


@app.post("/admin/api/users/{uid}")
async def admin_update_user(uid: int, request: Request):
    require_admin(request, write=True)
    get_user(uid)
    b = await body_json(request)
    if "password" in b:
        problem = security.password_problem(b.get("password"))
        if problem:
            raise ApiError(400, problem, "bad_password")
        pw_hash = await run_in_threadpool(security.hash_password, str(b["password"]))
        with db.tx() as t:
            t.execute("UPDATE users SET pw_hash = ? WHERE id = ?", (pw_hash, uid))
            t.execute("DELETE FROM tokens WHERE user_id = ?", (uid,))   # sign out everywhere with the old password
    caps = {}
    for k, high in (("max_per_day", 300), ("max_per_hour", 30)):
        if k in b:
            v = b.get(k)
            if v is None or str(v).strip() == "":
                caps[k] = None     # back to the global setting
            else:
                try:
                    caps[k] = int(float(v))
                except (TypeError, ValueError):
                    raise ApiError(400, "Limits must be numbers.", "bad_number")
                if not 0 <= caps[k] <= high:
                    raise ApiError(400, f"{k} must be between 0 and {high}.", "bad_number")
    with db.tx() as t:
        for k, v in caps.items():
            t.execute(f"UPDATE users SET {k} = ? WHERE id = ?", (v, uid))
        if b.get("revoke_sessions"):
            t.execute("DELETE FROM tokens WHERE user_id = ?", (uid,))
        if "pro" in b:
            t.execute("UPDATE users SET pro = ?, pro_changed_at = ? WHERE id = ?", (1 if b.get("pro") else 0, time.time(), uid))
        if "note" in b:
            t.execute("UPDATE users SET note = ? WHERE id = ?", (text(b.get("note"), 500), uid))
        if "name" in b:
            t.execute("UPDATE users SET name = ? WHERE id = ?", (text(b.get("name"), 80), uid))
    return {"ok": True, "user": get_user(uid)}


@app.post("/admin/api/users/{uid}/stop")
async def admin_stop(uid: int, request: Request):
    require_admin(request, write=True)
    user = get_user(uid)
    with db.tx() as t:
        t.execute("UPDATE users SET stop_requested_at = ? WHERE id = ?", (time.time(), uid))
    return {"ok": True, "online": user["online"], "user": get_user(uid)}


@app.post("/admin/api/users/{uid}/delete")
async def admin_delete(uid: int, request: Request):
    require_admin(request, write=True)
    get_user(uid)
    with db.tx() as t:
        t.execute("DELETE FROM users WHERE id = ?", (uid,))
    return {"ok": True}


@app.get("/admin/api/activity")
async def admin_activity(request: Request, user: int = 0, after: int = 0, limit: int = 200):
    """What the apps are doing, newest last: one user's lines, or everyone's."""
    require_admin(request)
    limit = max(1, min(500, limit))
    where, args = ["e.id > ?"], [after]
    if user:
        where.append("e.user_id = ?")
        args.append(user)
    rows = db.all_rows("SELECT e.id, e.at, e.text, e.user_id, u.email FROM events e JOIN users u ON u.id = e.user_id "
                       f"WHERE {' AND '.join(where)} ORDER BY e.id DESC LIMIT ?", (*args, limit))
    events = [dict(r) for r in reversed(rows)]
    last = events[-1]["id"] if events else after
    return {"ok": True, "events": events, "last_id": last}


@app.post("/admin/api/stop-all")
async def admin_stop_all(request: Request):
    require_admin(request, write=True)
    now = time.time()
    with db.tx() as t:
        n = t.execute("UPDATE users SET stop_requested_at = ? WHERE running = 1 AND last_seen >= ?",
                      (now, now - ONLINE_FOR)).rowcount
    return {"ok": True, "stopped": n}


@app.get("/admin/api/settings")
async def admin_settings(request: Request):
    require_admin(request)
    return {"ok": True, "settings": settings_json()}


MODEL_RE = re.compile(r"^[A-Za-z0-9._:/\-]{3,120}$")
INT_SETTINGS = {"free_ai_per_day": (0, 5000), "pro_ai_per_day": (1, 50000), "max_per_day": (0, 300), "max_per_hour": (0, 30),
                "min_version_github": (0, 10 ** 7), "min_version_play": (0, 10 ** 7)}


@app.post("/admin/api/settings")
async def admin_save_settings(request: Request):
    require_admin(request, write=True)
    b = await body_json(request)
    changes = {}
    if b.get("openrouter_key"):
        key = text(b["openrouter_key"], 300).strip("\"' ")
        if len(key) < 10 or any(c.isspace() for c in key):
            raise ApiError(400, "That does not look like an OpenRouter key.", "bad_key")
        changes["openrouter_key"] = key
    if b.get("clear_key"):
        changes["openrouter_key"] = ""
    if "model" in b:
        model = text(b["model"], 120)
        if not MODEL_RE.match(model):
            raise ApiError(400, "Type the model as OpenRouter names it, like openai/gpt-4o-mini.", "bad_model")
        changes["model"] = model
    for k, (low, high) in INT_SETTINGS.items():
        if k in b:
            try:
                v = int(float(b[k]))
            except (TypeError, ValueError):
                raise ApiError(400, f"{k} must be a number.", "bad_number")
            if not low <= v <= high:
                raise ApiError(400, f"{k} must be between {low} and {high}.", "bad_number")
            changes[k] = str(v)
    for k in ("signups_open", "new_users_pro"):
        if k in b:
            changes[k] = "1" if b[k] else "0"
    if "announcement" in b:
        changes["announcement"] = text(b.get("announcement"), 300)
    if "play_url" in b:
        url = text(b.get("play_url"), 300)
        if url and not url.startswith("https://"):
            raise ApiError(400, "The Google Play link must start with https://", "bad_url")
        changes["play_url"] = url
    with db.tx() as t:
        for k, v in changes.items():
            t.execute("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", (k, v))
    return {"ok": True, "settings": settings_json()}


@app.post("/admin/api/test-ai")
async def admin_test_ai(request: Request):
    require_admin(request, write=True)
    b = await body_json(request)
    s = db.settings()
    key = text(b.get("openrouter_key"), 300) or s.get("openrouter_key", "")
    model = text(b.get("model"), 120) or s.get("model", "")
    if not key:
        raise ApiError(400, "Add the OpenRouter key first.", "no_key")
    t0 = time.monotonic()
    try:
        data = await ai.chat(key, model, [{"role": "user", "content": "Reply with the word OK only."}], 10, 0)
    except ai.Upstream as e:
        raise ApiError(400, e.message, e.code)
    reply = ""
    try:
        reply = str(data["choices"][0]["message"]["content"] or "").strip()
    except (KeyError, IndexError, TypeError):
        pass
    return {"ok": True, "message": f"Connected in {time.monotonic() - t0:.1f}s. {data.get('model') or model} replied: "
                                   f"{reply[:40] or '(empty)'}"}
