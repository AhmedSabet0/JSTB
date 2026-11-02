"""Server tests with a fake OpenRouter. Run: python3 tests/test_server.py"""
import json
import os
import sys
import tempfile
import time

TMP = tempfile.mkdtemp()
os.environ.update({"DB_PATH": os.path.join(TMP, "t.db"), "ADMIN_PASSWORD": "admin-pass-123", "CONTACT_EMAIL": "help@example.com",
                   "OPERATOR_NAME": "Test Operator", "TIMEZONE": "Africa/Cairo"})
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import httpx  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import ai, main  # noqa: E402

UPSTREAM = {"mode": "ok", "calls": []}


def fake_openrouter(request):
    body = json.loads(request.content)
    UPSTREAM["calls"].append({"auth": request.headers.get("authorization"), "model": body.get("model"), "body": body})
    mode = UPSTREAM["mode"]
    if mode == "401":
        return httpx.Response(401, json={"error": {"message": "No auth credentials found", "code": 401}})
    if mode == "429":
        return httpx.Response(429, json={"error": {"message": "Rate limited", "code": 429}})
    if mode == "404":
        return httpx.Response(404, json={"error": {"message": "No endpoints found for bad/model", "code": 404}})
    return httpx.Response(200, json={"id": "gen-1", "model": body.get("model"), "choices": [
        {"message": {"role": "assistant", "content": "OK"}}], "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12}})


ai._client = httpx.AsyncClient(transport=httpx.MockTransport(fake_openrouter))

results = []


def check(name, cond, extra=None):
    results.append(bool(cond))
    print(("  ok   " if cond else "  FAIL ") + name + ("" if cond or extra is None else f"  -> {extra}"))


def main_test():
    with TestClient(main.app) as c:
        A = {"X-Requested-With": "admin"}

        def auth(tok):
            return {"Authorization": f"Bearer {tok}"}

        def err(r):
            try:
                return r.json()["error"]["code"]
            except Exception:  # noqa: BLE001
                return r.text

        print("\n== Public pages")
        r = c.get("/privacy")
        check("privacy policy names the contact and OpenRouter", r.status_code == 200 and "help@example.com" in r.text
              and "OpenRouter" in r.text and "Test Operator" in r.text)
        check("delete-account page has the form", "name=\"password\"" in c.get("/delete-account").text)
        check("config is public", c.get("/v1/config").json()["signups_open"] is True)

        print("\n== Accounts")
        r = c.post("/v1/auth/signup", json={"email": "Sara@Example.com ", "password": "secret-pass", "name": "Sara", "device": "Pixel 8"})
        check("sign up", r.status_code == 200 and r.json()["token"] and r.json()["user"]["email"] == "sara@example.com", r.text)
        tok = r.json()["token"]
        check("new user is not Pro", r.json()["user"]["pro"] is False)
        r = c.post("/v1/auth/signup", json={"email": "sara@example.com", "password": "another-pass"})
        check("same email twice refused", r.status_code == 409 and err(r) == "email_taken", r.text)
        check("bad email refused", err(c.post("/v1/auth/signup", json={"email": "nope", "password": "12345678"})) == "bad_email")
        check("short password refused", err(c.post("/v1/auth/signup", json={"email": "a@b.co", "password": "123"})) == "bad_password")
        r = c.post("/v1/auth/login", json={"email": "sara@example.com", "password": "wrong-pass"})
        check("wrong password", r.status_code == 401 and err(r) == "bad_login")
        r = c.post("/v1/auth/login", json={"email": "SARA@example.com", "password": "secret-pass", "device": "Galaxy"})
        check("sign in", r.status_code == 200 and r.json()["token"] != tok)
        tok2 = r.json()["token"]
        check("me", c.get("/v1/me", headers=auth(tok)).json()["user"]["name"] == "Sara")
        check("no token: 401", c.get("/v1/me").status_code == 401)
        check("bad token: 401", err(c.get("/v1/me", headers=auth("x" * 40))) == "signed_out")
        c.post("/v1/auth/logout", headers=auth(tok2))
        check("sign out ends that token only", c.get("/v1/me", headers=auth(tok2)).status_code == 401
              and c.get("/v1/me", headers=auth(tok)).status_code == 200)

        print("\n== AI before setup")
        msgs = [{"role": "user", "content": "hi"}]
        r = c.post("/v1/ai/chat", headers=auth(tok), json={"messages": msgs, "purpose": "apply"})
        check("not Pro: applying refused", r.status_code == 403 and err(r) == "not_active", r.text)
        r = c.post("/v1/ai/chat", headers=auth(tok), json={"messages": msgs, "purpose": "setup"})
        check("no key on the server yet", r.status_code == 403 and err(r) == "ai_not_configured", r.text)
        check("bad AI request", c.post("/v1/ai/chat", headers=auth(tok), json={"messages": "x"}).status_code == 400)

        print("\n== Admin")
        check("admin API needs sign-in", c.get("/admin/api/overview").status_code == 401)
        check("admin page served with CSP", "script-src 'self'" in c.get("/admin").headers.get("content-security-policy", ""))
        check("wrong admin password", c.post("/admin/api/login", json={"password": "nope"}).status_code == 401)
        r = c.post("/admin/api/login", json={"password": "admin-pass-123"})
        check("admin sign in sets a cookie", r.status_code == 200 and "aa_admin" in r.cookies)
        ov = c.get("/admin/api/overview").json()
        check("overview lists the user", ov["totals"]["users"] == 1 and ov["users"][0]["email"] == "sara@example.com"
              and ov["users"][0]["devices"] == 1, ov["totals"])
        r = c.post("/admin/api/settings", json={"model": "openai/gpt-4o-mini"})
        check("writes need the admin header", r.status_code == 403 and err(r) == "csrf")
        r = c.post("/admin/api/settings", headers=A, json={"openrouter_key": "sk-or-v1-abcdefghijklmnopqrstuvwxyz", "model": "openai/gpt-4o-mini"})
        s = r.json()["settings"]
        check("key saved, shown masked only", s["has_key"] and s["key_hint"].startswith("sk-or-v1-") and "abcdefghij" not in s["key_hint"], s)
        check("bad model name refused", c.post("/admin/api/settings", headers=A, json={"model": "bad model!"}).status_code == 400)
        check("limit out of range refused", c.post("/admin/api/settings", headers=A, json={"max_per_day": 999}).status_code == 400)
        r = c.post("/admin/api/test-ai", headers=A, json={})
        check("admin AI check", r.status_code == 200 and "replied: OK" in r.json()["message"], r.text)

        print("\n== AI through the server")
        UPSTREAM["calls"].clear()
        r = c.post("/v1/ai/chat", headers=auth(tok), json={"messages": msgs, "purpose": "setup", "max_tokens": 99999, "model": "evil/model"})
        call = UPSTREAM["calls"][-1] if UPSTREAM["calls"] else {}
        check("setup AI call works without Pro", r.status_code == 200 and r.json()["choices"][0]["message"]["content"] == "OK", r.text)
        check("server adds its key and its model (app cannot pick)", call.get("auth") == "Bearer sk-or-v1-abcdefghijklmnopqrstuvwxyz"
              and call.get("model") == "openai/gpt-4o-mini" and call["body"]["max_tokens"] == ai.MAX_TOKENS, call)
        c.post("/admin/api/settings", headers=A, json={"free_ai_per_day": 2})
        r = c.post("/v1/ai/chat", headers=auth(tok), json={"messages": msgs, "purpose": "check"})
        check("second call within the limit", r.status_code == 200, r.text)
        r = c.post("/v1/ai/chat", headers=auth(tok), json={"messages": msgs, "purpose": "setup"})
        check("daily AI limit for free accounts", r.status_code == 403 and err(r) == "ai_limit", r.text)
        ov = c.get("/admin/api/overview").json()
        check("AI use counted", ov["users"][0]["ai_today"] == 2 and ov["users"][0]["tokens_today"] == 24, ov["users"][0])

        uid = ov["users"][0]["id"]
        r = c.post(f"/admin/api/users/{uid}", headers=A, json={"pro": True, "note": "paid until June"})
        check("admin turns Pro on", r.json()["user"]["pro"] is True and r.json()["user"]["note"] == "paid until June")
        me = c.get("/v1/me", headers=auth(tok)).json()["user"]
        check("app sees Pro and the Pro AI limit", me["pro"] and me["ai_per_day"] == 800)
        r = c.post("/v1/ai/chat", headers=auth(tok), json={"messages": msgs, "purpose": "apply"})
        check("Pro: applying uses the AI", r.status_code == 200)

        for mode, status, code in (("401", 403, "ai_unavailable"), ("429", 429, "upstream_rate_limit"), ("404", 403, "bad_model")):
            UPSTREAM["mode"] = mode
            r = c.post("/v1/ai/chat", headers=auth(tok), json={"messages": msgs, "purpose": "apply"})
            check(f"OpenRouter {mode} -> app gets {status} {code}", r.status_code == status and err(r) == code, r.text)
        UPSTREAM["mode"] = "ok"

        print("\n== Reports, counts and Stop")
        ev = [{"job_id": "4001", "title": "DevOps Engineer", "company": "Acme", "status": "applied", "score": 8, "at": time.time()},
              {"job_id": "4002", "title": "SRE", "company": "Globex", "status": "applied", "score": 7, "at": time.time()},
              {"job_id": "4003", "title": "Sales", "company": "X", "status": "skipped_low_score", "at": time.time()},
              {"job_id": "4004", "title": "Cloud Engineer", "company": "Initech", "status": "stuck", "at": time.time()}]
        r = c.post("/v1/report", headers=auth(tok), json={"start": True, "running": True, "mode": "apply", "message": "Applying",
                                                           "app_version": "2.0", "device": "Pixel 8", "events": ev})
        check("report accepted, no stop", r.status_code == 200 and r.json()["stop"] is False and r.json()["pro"] is True, r.text)
        c.post("/v1/report", headers=auth(tok), json={"running": True, "mode": "apply", "events": ev[:1]})  # resent: no double count
        u = c.get("/admin/api/overview").json()["users"][0]
        check("admin sees the run and the counts", u["online"] and u["state"] == "running" and u["applied_today"] == 2
              and u["applied_total"] == 2 and u["problems_today"] == 1 and u["app_version"] == "2.0", u)
        d = c.get(f"/admin/api/users/{uid}").json()
        check("details list the applications with links", len(d["applications"]) == 3
              and d["applications"][0]["url"].startswith("https://www.linkedin.com/jobs/view/"), d["applications"])
        r = c.post(f"/admin/api/users/{uid}/stop", headers=A, json={})
        check("admin Stop", r.json()["online"] is True and r.json()["user"]["stop_pending"] is True)
        r = c.post("/v1/report", headers=auth(tok), json={"running": True, "mode": "apply"})
        check("next report tells the app to stop", r.json()["stop"] is True and "admin" in r.json()["reason"], r.json())
        r = c.post("/v1/report", headers=auth(tok), json={"running": True, "mode": "apply"})
        check("the stop is sent once", r.json()["stop"] is False)
        c.post("/v1/report", headers=auth(tok), json={"running": False, "message": "Stopped by the admin."})
        u = c.get("/admin/api/overview").json()["users"][0]
        check("run ended", u["state"] == "idle" and not u["online"], u["state"])
        c.post(f"/admin/api/users/{uid}/stop", headers=A, json={})
        r = c.post("/v1/report", headers=auth(tok), json={"start": True, "running": True, "mode": "apply"})
        check("a stop pressed before a new run does not stop it", r.json()["stop"] is False)
        r = c.post("/admin/api/stop-all", headers=A, json={})
        check("Stop everyone", r.json()["stopped"] == 1)
        check("... reaches the app", c.post("/v1/report", headers=auth(tok), json={"running": True}).json()["stop"] is True)
        c.post(f"/admin/api/users/{uid}", headers=A, json={"pro": False})
        r = c.post("/v1/report", headers=auth(tok), json={"running": True, "mode": "apply"})
        check("Pro turned off: the app stops", r.json()["stop"] is True and "turned off" in r.json()["reason"], r.json())
        c.post("/admin/api/settings", headers=A, json={"max_per_day": 25})
        check("daily cap reaches the app", c.post("/v1/report", headers=auth(tok), json={"running": False}).json()["max_per_day"] == 25)

        print("\n== Password reset and deletion")
        r = c.post(f"/admin/api/users/{uid}", headers=A, json={"password": "brand-new-pass"})
        check("admin resets a password and signs the user out", r.status_code == 200 and c.get("/v1/me", headers=auth(tok)).status_code == 401)
        tok = c.post("/v1/auth/login", json={"email": "sara@example.com", "password": "brand-new-pass"}).json()["token"]
        check("... the new password works", c.get("/v1/me", headers=auth(tok)).status_code == 200)
        r = c.post("/v1/me/delete", headers=auth(tok), json={"password": "wrong"})
        check("in-app delete needs the password", r.status_code == 400)
        r = c.post("/v1/me/delete", headers=auth(tok), json={"password": "brand-new-pass"})
        check("in-app delete", r.status_code == 200 and c.get("/v1/me", headers=auth(tok)).status_code == 401)
        ov = c.get("/admin/api/overview").json()
        check("... removes the user and the reported jobs", ov["totals"]["users"] == 0 and ov["totals"]["applied_total"] == 0)

        c.post("/v1/auth/signup", json={"email": "web@example.com", "password": "web-pass-123"})
        r = c.post("/delete-account", content="email=web%40example.com&password=bad",
                   headers={"Content-Type": "application/x-www-form-urlencoded"})
        check("web delete: wrong password", r.status_code == 400 and "Wrong email or password" in r.text)
        r = c.post("/delete-account", content="email=web%40example.com&password=web-pass-123",
                   headers={"Content-Type": "application/x-www-form-urlencoded"})
        check("web delete works", r.status_code == 200 and "Account deleted" in r.text
              and c.get("/admin/api/overview").json()["totals"]["users"] == 0)

        print("\n== New account settings")
        c.post("/admin/api/settings", headers=A, json={"signups_open": False})
        r = c.post("/v1/auth/signup", json={"email": "late@example.com", "password": "late-pass-1"})
        check("sign-ups closed", r.status_code == 403 and err(r) == "signups_closed")
        c.post("/admin/api/settings", headers=A, json={"signups_open": True, "new_users_pro": True})
        r = c.post("/v1/auth/signup", json={"email": "tester@example.com", "password": "tester-pass"})
        check("testers start with Pro", r.json()["user"]["pro"] is True)
        c.post("/admin/api/logout", headers=A)
        check("admin sign out", c.get("/admin/api/overview").status_code == 401)

    ok = sum(results)
    print(f"\n{ok}/{len(results)} server checks passed")
    return ok == len(results)


if __name__ == "__main__":
    sys.exit(0 if main_test() else 1)
