"""End-to-end test of the Chrome extension: a real JSTB server (fake Google, fake AI), mock job pages and Chrome with
the extension loaded. Run: python3 extension/test/test_extension.py [path to a LinkedIn mock server script]"""
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]
TMP = tempfile.mkdtemp()
os.environ.update({"DB_PATH": os.path.join(TMP, "t.db"), "ADMIN_PASSWORD": "admin-pass-123", "CONTACT_EMAIL": "help@example.com",
                   "PUBLIC_URL": "http://127.0.0.1:8765"})
sys.path.insert(0, str(ROOT / "server"))
sys.path.insert(0, str(ROOT / "server" / "tests"))
import fake_google  # noqa: E402
os.environ.update(fake_google.URLS)
import httpx  # noqa: E402
import uvicorn  # noqa: E402
from app import ai, db, drive, main  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

CLIENT_ID, SECRET = "1234567890-abcdefghij.apps.googleusercontent.com", "GOCSPX-test-secret-123456"
fake = fake_google.FakeGoogle(CLIENT_ID, SECRET)
fake.email, fake.name = "sara@example.com", "Sara Adel"
drive._client = httpx.AsyncClient(transport=httpx.MockTransport(fake.handle))
ASKED = []


def fake_ai(request):
    body = json.loads(request.content)
    text = body["messages"][-1]["content"]
    qs = json.loads(text.split("QUESTIONS:\n", 1)[1].split("\n\nRULES:", 1)[0])
    out = []
    for q in qs:
        ASKED.append(q["question"] + (" [retry]" if q.get("rejected_with") else ""))
        ql = q["question"].lower()
        if q["type"] == "choice":
            a = next((o for o in q["options"] if o.lower().startswith("yes")), q["options"][0])
        elif q["type"] == "multi":
            a = [q["options"][0]]
        elif q["type"] == "numeric":
            a = "4" if "year" in ql else "20000"
        elif q["type"] == "textarea":
            a = "I run AWS and Kubernetes platforms and would like to do the same for you."
        else:
            a = "Cairo" if "city" in ql else "4"
        out.append({"n": q["n"], "answer": a})
    return httpx.Response(200, json={"id": "x", "model": "m", "usage": {"total_tokens": 50},
                                     "choices": [{"message": {"role": "assistant", "content": json.dumps({"answers": out})}}]})


ai._client = httpx.AsyncClient(transport=httpx.MockTransport(fake_ai))

FORM = """<!doctype html><html><head><meta charset="utf-8"><title>Platform Engineer - Nile Systems</title></head>
<body><h1>Platform Engineer</h1><p>Nile Systems is hiring a platform engineer to run AWS, Terraform and Kubernetes.</p>
<form id="app" method="post" action="/ats/sent" enctype="multipart/form-data">
<label for="fn">First name *</label><input id="fn" name="first_name" required>
<label for="ln">Last name *</label><input id="ln" name="last_name" required>
<label for="em">Email *</label><input id="em" type="email" name="email" required>
<label for="ph">Phone</label><input id="ph" type="tel" name="phone">
<label for="sal">Expected salary (EGP per month)</label><input id="sal" type="number" name="salary">
<label for="np">Notice period</label><select id="np" name="notice"><option value="">Select...</option><option>Immediately</option><option>1 month</option></select>
<fieldset><legend>Are you willing to relocate?</legend>
<label><input type="radio" name="reloc" value="yes"> Yes</label><label><input type="radio" name="reloc" value="no"> No</label></fieldset>
<label for="yrs">Years of experience with Kubernetes *</label><input id="yrs" name="k8s" required>
<label for="cv">Resume</label><input id="cv" type="file" name="cv">
<label><input type="checkbox" name="privacy" id="pp" required> I agree to the privacy policy *</label>
<button type="submit">Submit application</button></form></body></html>"""
WUZZUF = """<!doctype html><html><head><meta charset="utf-8"><title>Cloud Engineer | Wuzzuf</title></head><body>
<h1>Cloud Engineer</h1><p>Delta Tech - Cairo, Egypt</p><button id="go">Apply For Job</button><div id="box"></div>
<script>document.getElementById('go').onclick = function () {
  document.getElementById('box').innerHTML = '<form id="q"><label for="a1">How many years of experience do you have with AWS?</label>'
    + '<input id="a1" name="a1" required><label for="a2">Why do you want this job?</label><textarea id="a2" name="a2" required></textarea>'
    + '<button type="submit">Submit Application</button></form>';
  document.getElementById('q').onsubmit = function (e) { e.preventDefault();
    fetch('/wz/sent', {method: 'POST', body: JSON.stringify({a1: a1.value, a2: a2.value})}).then(function () {
      document.body.innerHTML = '<h1>Your application was sent. Applied successfully.</h1>'; }); };
};</script></body></html>"""
RESUME_SITE = """<!doctype html><html><head><meta charset="utf-8"><title>System Administrator</title></head><body><main id="m">
<h2>Start Your Application</h2><a role="button" href="#" id="auto">Autofill with Resume</a><a role="button" href="#" id="man">Apply Manually</a>
<a role="button" href="#">Use My Last Application</a></main>
<script>
const m = document.getElementById('m');
document.getElementById('man').onclick = function () { fetch('/wd/manual', {method: 'POST', body: '1'}); };
document.getElementById('auto').onclick = function (e) { e.preventDefault(); setTimeout(function () {
  m.innerHTML = '<h2>Autofill with Resume</h2><div>Drop your resume here</div><input type="file" id="up" style="display:none"><button id="c1">Continue</button>';
  document.getElementById('c1').onclick = function () {
    const f = document.getElementById('up').files[0];
    if (!f) { m.insertAdjacentHTML('beforeend', '<div role="alert" class="error">A resume is required</div>'); return; }
    m.innerHTML = '<h2>My Information</h2><label for="n">Full name</label><input id="n" value="Sara Adel"><label for="e">Email</label><input id="e" type="email" value="sara@example.com">'
      + '<label for="y">How many years of experience do you have with Linux?</label><input id="y">'
      + '<div class="x1"><div class="x2">Current city <span>*</span></div><div><input id="c"></div><div class="x9">0/20</div></div>'
      + '<div class="x1"><div class="x2">Are you willing to relocate?</div><div><div><input type="radio" name="rl" value="y"><span>Yes</span></div>'
      + '<div><input type="radio" name="rl" value="n"><span>No</span></div></div></div>'
      + '<div componentkey="k1"><p class="k4">Have you set up and managed production systems on AWS (for example EC2 or EKS)?*</p>'
      + '<fieldset aria-describedby="error-message-r3" role="radiogroup"><div><div><div><div><input id="r3y" aria-label="Have you set up and managed production systems on AWS (for example EC2 or EKS)?" type="radio" name="radio-group-r3"><label for="r3y" style="display:inline-block;width:20px;height:20px"></label></div><div><p>Yes</p></div></div></div>'
      + '<div><div><div><input id="r3n" aria-label="Have you set up and managed production systems on AWS (for example EC2 or EKS)?" type="radio" name="radio-group-r3"><label for="r3n" style="display:inline-block;width:20px;height:20px"></label></div><div><p>No</p></div></div></div></div></fieldset>'
      + '<p class="k4">This field is required</p></div><button id="c2">Submit</button>';
    document.getElementById('c2').onclick = function () {
      fetch('/wd/sent', {method: 'POST', body: JSON.stringify({file: f.name, y: document.getElementById('y').value, c: document.getElementById('c').value,
        rl: (document.querySelector('input[name=rl]:checked') || {}).value, r3: (document.querySelector('input[name=radio-group-r3]:checked') || {}).id})}).then(function () {
        m.innerHTML = '<h2>Thank you for applying</h2>'; }); };
  }; }, 800); };
</script></body></html>"""
POSTED = {}


class Pages(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def page(self, body):
        data = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlparse(self.path).path
        self.page(FORM if path == "/ats/apply" else RESUME_SITE if path == "/wd/apply" else WUZZUF if path.startswith("/wz/jobs") else "<h1>Nothing here</h1>")

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        POSTED[urlparse(self.path).path] = raw
        self.page("<h1>Thank you for applying!</h1><p>We have received your application.</p>")


pages = ThreadingHTTPServer(("127.0.0.1", 0), Pages)
threading.Thread(target=pages.serve_forever, daemon=True).start()
PAGES = f"http://127.0.0.1:{pages.server_port}"

LI = None
mock = None
if len(sys.argv) > 1:          # the LinkedIn mock of the app's engine tests
    mock = subprocess.Popen([sys.executable, sys.argv[1]], stdout=subprocess.PIPE, text=True)
    LI = "http://127.0.0.1:" + re.search(r"MOCK_PORT=(\d+)", mock.stdout.readline()).group(1) + "/classic"

srv = uvicorn.Server(uvicorn.Config(main.app, host="127.0.0.1", port=8765, log_level="warning"))
threading.Thread(target=srv.run, daemon=True).start()
BASE = "http://127.0.0.1:8765"
for _ in range(100):
    try:
        httpx.get(BASE + "/v1/config", timeout=1)
        break
    except httpx.HTTPError:
        time.sleep(0.1)
admin = httpx.Client(base_url=BASE, headers={"X-Requested-With": "admin"})
admin.post("/admin/api/login", json={"password": "admin-pass-123"})
r = admin.post("/admin/api/settings", json={"google_client_id": CLIENT_ID, "google_client_secret": SECRET,
                                            "openrouter_key": "sk-or-v1-" + "a" * 64})
assert r.status_code == 200, r.text

FAILED = []


def check(name, ok, extra=""):
    print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else "   " + str(extra)[:600]))
    if not ok:
        FAILED.append(name)


def seed(email):
    """Pro, a CV, setup answers and matched jobs for the account that just signed in."""
    c = db.conn()
    uid = c.execute("SELECT id FROM users WHERE email = ?", (email,)).fetchone()["id"]
    jobs = [("wz1", "Cloud Engineer", "Delta Tech", PAGES + "/wz/jobs/p/abc-cloud-engineer", 90),
            ("ats1", "Platform Engineer", "Nile Systems", PAGES + "/ats/apply", 80),
            ("wd1", "System Administrator", "Rail Co", PAGES + "/wd/apply", 70)]
    if LI:
        jobs += [("111", "DevOps Engineer", "Acme", LI + "/jobs/view/111/", 95)]
    with db.tx() as t:
        t.execute("UPDATE users SET pro = 1 WHERE id = ?", (uid,))
        for jid, title, company, url, score in jobs:
            t.execute("INSERT INTO server_jobs(id, title, company, location, url, posted_date, collected_at, keywords) "
                      "VALUES (?, ?, ?, 'Cairo', ?, '', ?, '')", (jid, title, company, url, time.time()))
            t.execute("INSERT INTO user_job_matches(user_id, job_id, score, why, status, updated_at) VALUES (?, ?, ?, '', 'new', ?)",
                      (uid, jid, score, time.time()))
    return uid, len(jobs)


CV_TEXT = "Sara Adel\nCloud engineer, Cairo\nAWS, Terraform, Kubernetes since 2021.\nlinkedin.com/in/sara-adel\n" + "Built and ran production clusters, pipelines and monitoring for three product teams. " * 3
PDF = b"%PDF-1.4\n" + b"x" * 400 + b"\n%%EOF"

with sync_playwright() as p:
    ctx = p.chromium.launch_persistent_context(
        os.path.join(TMP, "profile"), channel="chrome", headless=False,
        args=["--headless=new", f"--disable-extensions-except={ROOT / 'extension'}", f"--load-extension={ROOT / 'extension'}", "--no-sandbox"])
    sw = ctx.service_workers[0] if ctx.service_workers else ctx.wait_for_event("serviceworker", timeout=15000)
    ext = sw.url.split("/")[2]
    dev = [[LI, "linkedin"]] if LI else []
    sw.evaluate("(a) => chrome.storage.local.set({server: a[0], devDrivers: a[1]})", [BASE, dev + [[PAGES + "/wz", "wuzzuf"]]])
    popup = ctx.new_page()
    popup.goto(f"chrome-extension://{ext}/popup.html")
    check("the popup asks to sign in", popup.locator("#signin").is_visible())

    # ---- sign in with Google, through the server
    seen = []
    ctx.route("https://accounts.google.test/**", lambda route: (seen.append(route.request.url), route.fulfill(body="<h1>Google</h1>")))
    with ctx.expect_page() as opened:
        popup.click("#signin")
    gtab = opened.value
    for _ in range(100):
        if seen:
            break
        popup.wait_for_timeout(100)
    q = fake.consent(seen[0])
    try:
        gtab.goto(BASE + "/oauth/google/callback?code=" + q["code"] + "&state=" + q["state"])
    except Exception:
        pass                       # the page tries to open the phone app: nothing to open here
    popup.wait_for_selector("#toggle", state="visible", timeout=30000)
    check("signed in with Google (the tab closes by itself)", "sara@example.com" in popup.inner_text("#who") and gtab.is_closed(),
          popup.inner_text("#who"))

    token = sw.evaluate("chrome.storage.local.get('token').then((s) => s.token)")
    H = {"Authorization": "Bearer " + token}
    uid, n_jobs = seed("sara@example.com")
    popup.click("#toggle")
    popup.wait_for_timeout(1500)
    check("it does not start without a CV", "CV" in popup.inner_text("#message"), popup.inner_text("#message"))
    r = httpx.post(BASE + "/v1/me/cv", headers=H, json={"name": "Sara Adel CV.pdf", "data": base64.b64encode(PDF).decode(),
                                                        "text": CV_TEXT, "base": 0, "t": time.time()})
    assert r.status_code == 200, r.text
    changes = [{"k": "profile", "id": k, "d": {"v": v}, "base": 0, "t": time.time()} for k, v in
               {"phone": "+201012345678", "city": "Cairo", "expected_salary": "45000", "currency": "EGP", "notice_days": "30",
                "relocate": "No"}.items()]
    assert httpx.post(BASE + "/v1/me/data", headers=H, json={"changes": changes}).status_code == 200

    # ---- a fast pace for the test, then start
    popup.reload()
    popup.click("#settingsBox summary")
    popup.fill("#perHour", "30")
    popup.click("#save")
    popup.click("#toggle")
    popup.wait_for_timeout(1000)
    check("it starts", popup.inner_text("#toggle") == "Stop", popup.inner_text("#message"))

    def state():
        return sw.evaluate("chrome.storage.local.get(null).then((s) => ({done: s.done, running: s.running, nextAt: s.nextAt, "
                           "active: s.active, log: s.log.map((l) => l.text), times: s.times.length}))")

    end = time.time() + 240
    while time.time() < end:
        s = state()
        if len(s["done"]) >= n_jobs:
            break
        if not s["active"] and s["nextAt"] > time.time():     # do not wait out the pace in a test
            sw.evaluate("chrome.storage.local.set({nextAt: 0}).then(() => chrome.alarms.create('tick', {when: Date.now() + 200}))")
        time.sleep(1)
    s = state()
    print("\n".join("    " + l for l in s["log"]))
    done = s["done"]
    check("the Wuzzuf job is applied to", done.get("li:wz1") == "applied", done)
    sent = json.loads(POSTED.get("/wz/sent", b"{}"))
    check("its questions are answered by the AI", sent.get("a1") == "5" and "Kubernetes" in sent.get("a2", ""), sent)
    check("the company form is applied to", done.get("li:ats1") == "applied", done)
    form = POSTED.get("/ats/sent", b"").decode("latin-1")
    wd = json.loads(POSTED.get("/wd/sent", b"{}"))
    check("a site with Autofill with Resume is applied to that way", done.get("li:wd1") == "applied" and "/wd/manual" not in POSTED
          and wd.get("file") == "Sara Adel CV.pdf", (done, wd, list(POSTED)))
    check("what the site did not fill is answered after it", wd.get("y") == "1", wd)
    check("questions without a label tag are still read", (wd.get("c"), wd.get("rl")) == ("Cairo", "n"), wd)
    check("LinkedIn's new radio questions are read and ticked", wd.get("r3") == "r3y" and any(a.startswith("Have you set up and managed") for a in ASKED), (wd, ASKED))

    def part(name):
        m = re.search(r'name="%s"\r\n\r\n(.*?)\r\n--' % name, form, re.S)
        return m.group(1) if m else None

    check("name, email and phone come from the account", (part("first_name"), part("last_name"), part("email"), part("phone"))
          == ("Sara", "Adel", "sara@example.com", "+201012345678"), form[:900])
    check("salary, notice period and relocation come from the setup answers",
          (part("salary"), part("notice"), part("reloc")) == ("45000", "1 month", "no"), (part("salary"), part("notice"), part("reloc")))
    check("years of experience are counted from the first job in the CV", part("k8s") == "5" and not any("Kubernetes" in a for a in ASKED), (part("k8s"), ASKED))
    check("the CV is attached and the policy box ticked", 'filename="Sara Adel CV.pdf"' in form and part("privacy") == "on", form[-600:])
    if LI:
        check("the LinkedIn Easy Apply job is applied to", done.get("li:111") == "applied", done)
    check("each one counts toward the pace", s["times"] == n_jobs, s["times"])
    rows = {r["job_id"]: r["status"] for r in db.conn().execute("SELECT job_id, status FROM user_job_matches WHERE user_id = ?", (uid,))}
    check("the server marks the jobs as applied", all(v == "applied" for v in rows.values()), rows)
    apps = [r["job_id"] for r in db.conn().execute("SELECT job_id FROM applications WHERE user_id = ? AND status = 'applied'", (uid,))]
    check("the applications are in the account's report", len(apps) == n_jobs, apps)
    popup.reload()
    popup.wait_for_timeout(2500)
    check("the popup shows the count and the activity", popup.inner_text("#today").startswith(str(n_jobs)) and "Applied" in popup.inner_text("#log"),
          popup.inner_text("#today"))
    left = [pg.url for pg in ctx.pages if pg.url.startswith("http")]
    check("no job tab is left open", not left, left)
    popup.click("#toggle")
    popup.wait_for_timeout(800)
    check("Stop stops it", state()["running"] is False)
    popup.click("#signout")
    popup.wait_for_selector("#signin", state="visible")
    check("Sign out works", sw.evaluate("chrome.storage.local.get('token').then((s) => s.token)") == "")
    ctx.close()

if mock:
    mock.kill()
print("\n%s" % ("ALL PASSED" if not FAILED else "FAILED: " + ", ".join(FAILED)))
sys.exit(1 if FAILED else 0)
