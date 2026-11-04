"""Public pages: home, privacy policy and account deletion (Google Play asks for the last two as links)."""
import html
import os

APP_NAME = os.environ.get("APP_NAME", "Auto Apply")
OPERATOR = os.environ.get("OPERATOR_NAME", "") or APP_NAME
CONTACT = os.environ.get("CONTACT_EMAIL", "")
POLICY_DATE = os.environ.get("POLICY_DATE", "November 3, 2026")

e = html.escape

STYLE = """
:root{color-scheme:light dark;--paper:#eff3f8;--surface:#fff;--ink:#15233f;--ink-2:#4a5874;--line:#dce3ed;--action:#0a66c2;
--action-ink:#fff;--error:#c2362b;--good:#11805b}
@media (prefers-color-scheme:dark){:root{--paper:#0e1522;--surface:#162033;--ink:#e7edf6;--ink-2:#9aa8bf;--line:#26344d;
--action:#5aa2ee;--action-ink:#08131f;--error:#f27d72;--good:#4cc795}}
*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);
font:16px/1.6 system-ui,-apple-system,Roboto,"Segoe UI",Arial,sans-serif}
main{max-width:720px;margin:0 auto;padding:32px 16px 64px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:18px;padding:24px 22px;margin-top:18px}
h1{font-size:28px;line-height:1.2;margin:0 0 6px}h2{font-size:18px;margin:26px 0 6px}p,li{color:var(--ink)}
.muted{color:var(--ink-2)}a{color:var(--action)}ul{padding-left:20px}
.brand{display:flex;align-items:center;gap:10px;font-weight:700}
.brand svg{width:32px;height:32px}.brand rect{fill:var(--action)}.brand path{fill:var(--action-ink)}
label{display:block;font-weight:600;margin:14px 0 6px}
input{width:100%;font:inherit;padding:12px 14px;border:1px solid var(--line);border-radius:12px;background:var(--paper);color:var(--ink)}
button{margin-top:18px;font:inherit;font-weight:700;padding:12px 20px;border:0;border-radius:12px;background:var(--error);color:#fff;cursor:pointer}
.ok{color:var(--good);font-weight:700}.bad{color:var(--error);font-weight:700}
.links{display:flex;gap:18px;flex-wrap:wrap;margin-top:18px}
.btn{display:inline-block;margin-top:18px;font-weight:700;padding:12px 20px;border-radius:12px;background:var(--action);
color:var(--action-ink);text-decoration:none}
"""

LOGO = ('<svg viewBox="0 0 64 64" aria-hidden="true"><rect width="64" height="64" rx="16"/>'
        '<path d="M15 31 49 16 38 49 31 37z"/></svg>')


def page(title, body):
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{e(title)}</title><style>{STYLE}</style></head>
<body><main><div class="brand">{LOGO}<span>{e(APP_NAME)}</span></div>{body}</main></body></html>"""


def contact_line():
    return f'<a href="mailto:{e(CONTACT)}">{e(CONTACT)}</a>' if CONTACT else "the contact address on the app's Google Play page"


def home():
    return page(APP_NAME, f"""<div class="card"><h1>{e(APP_NAME)}</h1>
<p class="muted">An Android app that helps you apply to jobs that fit your CV: it keeps your answers ready, fills
application forms, tracks every application, and (in its GitHub version) applies to LinkedIn Easy Apply jobs at a pace you
set. Your CV, answers and jobs are the same on every phone you sign in on, and in your Google Drive if you connect it.</p>
<p class="muted">{e(APP_NAME)} is not affiliated with, endorsed by or sponsored by LinkedIn.</p>
<div class="links"><a href="/privacy">Privacy policy</a><a href="/delete-account">Delete your account</a></div></div>""")


def privacy():
    return page(f"Privacy policy - {APP_NAME}", f"""<div class="card"><h1>Privacy policy</h1>
<p class="muted">{e(APP_NAME)} &middot; last updated {e(POLICY_DATE)}</p>
<p>This policy explains what the {e(APP_NAME)} Android app and its server collect, why, and what you can do about it.
The service is run by {e(OPERATOR)}. Questions: {contact_line()}.</p>

<h2>What stays on your phone only</h2>
<ul><li>Your LinkedIn sign-in. You sign in on LinkedIn's own page inside the app. We never receive your LinkedIn password,
and the LinkedIn session (cookies) stays on your phone.</li>
<li>The app's update downloads, its pacing state and its debug files.</li></ul>

<h2>What our server keeps</h2>
<ul><li><b>Your account:</b> name and email address, and either a password (stored only as a salted scrypt hash) or,
when you continue with Google, your Google account ID.</li>
<li><b>Your data, so it is the same on every phone you sign in on:</b> your CV file and its text, the answers you save
(the setup answers and "Your info"), your settings and job titles, the notes for the AI, the jobs you saved to apply to
and the jobs the app handled, with their stage and notes. Your phone keeps its own copy too.</li>
<li><b>Activity the app reports:</b> whether auto apply is running, its status, the app version and phone model, and for
each job it applied to (or could not finish): the LinkedIn job ID, job title, company name, result, match score and time.
This lets us show you and the service admin how many applications were sent, and lets the admin stop auto apply.</li>
<li><b>The app's activity log:</b> the steps the app takes (searches, the jobs it looked at and why it skipped or applied,
the answers it filled in, errors), the same lines you see under Activity in the app. The admin uses it to help you and
to find problems. The newest lines are kept; older ones are deleted automatically.</li>
<li><b>Jobs you track in the app:</b> when you mark a job as applied, or change its stage (interview, offer...), the job
title, company, link and stage.</li>
<li><b>AI usage:</b> how many AI requests your account made each day and how many tokens they used, to enforce daily
limits.</li></ul>
<p>The server does not keep access logs of your requests. Your IP address is only held in memory for a short time to
limit repeated sign-in attempts.</p>

<h2>What passes through our server to the AI</h2>
<p>To pick matching jobs and fill in application forms, the app sends the AI the text of your CV, your saved answers and
notes, the job description and the form's questions. These go through our server to OpenRouter (openrouter.ai), which
forwards them to the AI model provider that produces the answer. Our server does not store this content. OpenRouter's
privacy policy and the model provider's terms apply to it.</p>

<h2>Sign in with Google and Google Drive</h2>
<p>When you continue with Google, Google gives our server your name, email address and Google account ID; they make
your account and sign you in. The same consent connects Google Drive. An account made earlier with an email and a
password can connect Drive in the app (Settings &rarr; Your data).</p>
<p>With Google Drive connected, our server makes a folder named "Auto Apply" in your Drive with a copy of
your CV, a "My answers" sheet and a "My jobs" sheet, keeps them up to date, and reads the changes you make to those
files so they come back to the app. It uses Google's <i>drive.file</i> permission: it can see and change only the files
it made (or that you open with it), not the rest of your Drive. It also reads your Google account's email address to
show which account is connected. The Google sign-in tokens are stored encrypted on our server and are used for nothing
else.</p>
<p>You can disconnect at any time in the app (Settings &rarr; Google Drive &rarr; Disconnect) or at
<a href="https://myaccount.google.com/permissions">myaccount.google.com/permissions</a>. The files stay in your Drive;
they are yours to keep or delete.</p>
<p>{e(APP_NAME)}'s use and transfer of information received from Google APIs adheres to the
<a href="https://developers.google.com/terms/api-services-user-data-policy">Google API Services User Data Policy</a>,
including the Limited Use requirements.</p>

<h2>What we do not do</h2>
<ul><li>We do not sell your data or share it with advertisers.</li><li>The app has no ads and no third-party analytics.
</li><li>We share data only with OpenRouter and with your own Google Drive (when you connect it), as described above, or
when the law requires it.</li></ul>

<h2>How long we keep it</h2>
<p>Your account, your data, activity and usage records are kept while your account exists. When you delete your
account they are deleted from the server right away. Deleting a fact, a job or your CV in the app deletes it from the
server too.</p>

<h2>Deleting your account</h2>
<p>In the app: Settings &rarr; Account &rarr; Delete account. Or on the web: <a href="/delete-account">delete your
account</a>. Both delete your account, your CV, answers, settings and jobs kept on the server, your reported activity
and your AI usage records, and disconnect Google Drive (the files in your Drive stay yours). If you can no longer sign in,
email {contact_line()} from your account's email address and we will delete it within 7 days.</p>

<h2>Security</h2>
<p>The app talks to the server over HTTPS only. Passwords are hashed, and sign-in tokens are stored only as hashes.</p>

<h2>Children</h2>
<p>{e(APP_NAME)} is meant for job seekers aged 18 or over and is not directed to children.</p>

<h2>LinkedIn</h2>
<p>{e(APP_NAME)} is not affiliated with LinkedIn. You use your own LinkedIn account and are responsible for following
LinkedIn's terms.</p>

<h2>Changes</h2>
<p>If this policy changes, the new version is posted on this page with a new date.</p></div>""")


def delete_form(message="", ok=False):
    note = ""
    if message:
        note = f'<p class="{"ok" if ok else "bad"}">{e(message)}</p>'
    if ok:
        return page(f"Account deleted - {APP_NAME}", f'<div class="card"><h1>Account deleted</h1>{note}'
                    f'<p class="muted">Data on your phone is removed when you uninstall the app.</p></div>')
    return page(f"Delete your account - {APP_NAME}", f"""<div class="card"><h1>Delete your {e(APP_NAME)} account</h1>
<p class="muted">This deletes your account and everything our server keeps for it, right away: your CV, answers,
settings and jobs, the activity the app reported and your AI usage records. It also disconnects Google Drive (the files
in your Drive stay yours). It cannot be undone. The copy on your phone (and your LinkedIn sign-in) is removed when you
uninstall the app.</p>
{note}
<form method="post" action="/delete-account" autocomplete="off">
<label for="email">Email of your account</label><input id="email" name="email" type="email" required>
<label for="password">Password</label><input id="password" name="password" type="password" required>
<button type="submit">Delete my account</button></form>
<p>Made your account with Google? <a href="/delete-account/google">Delete it by signing in with Google</a>.</p>
<p class="muted">Forgot your password? Email {contact_line()} from your account's email address and we will delete the
account within 7 days.</p></div>""")


def drive_result(ok, text):
    """The page Google sends the browser back to, after connecting Google Drive."""
    back = '<a class="btn" href="autoapply://drive">Back to Auto Apply</a>'
    if ok:
        who = f" as <b>{e(text)}</b>" if text else ""
        return page(f"Google Drive connected - {APP_NAME}", f"""<div class="card"><h1>Google Drive is connected</h1>
<p class="ok">Connected{who}.</p>
<p>Your <b>Auto Apply</b> folder is being made in your Drive: your CV, <b>My answers</b> and <b>My jobs</b>. What you change
there comes back to the app.</p><p class="muted">You can close this page and go back to the app.</p>{back}</div>""")
    return page(f"Google Drive not connected - {APP_NAME}", f"""<div class="card"><h1>Google Drive is not connected</h1>
<p class="bad">{e(text)}</p><p class="muted">Go back to the app and try again.</p>{back}</div>""")


def signed_in(email, code):
    """After Sign in with Google: back to the app with a one-time code (worthless without the app's own verifier)."""
    link = f"autoapply://auth?code={e(code)}"
    return page(f"Signed in - {APP_NAME}", f"""<div class="card"><h1>You are signed in</h1>
<p class="ok">Signed in with Google as <b>{e(email)}</b>.</p>
<p>Your <b>Auto Apply</b> folder in this Google Drive keeps your CV, answers and jobs. Go back to the app to continue.</p>
<a class="btn" id="back" href="{link}">Back to Auto Apply</a>
<p class="muted">If the app does not open, open it yourself: it finishes signing in by itself.</p></div>
<script>setTimeout(function () {{ location.href = "{link}"; }}, 300);</script>""")


def google_linked(email):
    return page(f"Google added - {APP_NAME}", f"""<div class="card"><h1>Google is added to your account</h1>
<p class="ok">From now on you can sign in with Google as <b>{e(email)}</b>, on any phone.</p>
<p>Your <b>Auto Apply</b> folder in this Google Drive keeps your CV, answers and jobs.</p>
<a class="btn" href="autoapply://drive">Back to Auto Apply</a></div>
<script>setTimeout(function () {{ location.href = "autoapply://drive"; }}, 300);</script>""")


def signin_failed(message):
    return page(f"Not signed in - {APP_NAME}", f"""<div class="card"><h1>You are not signed in</h1>
<p class="bad">{e(message)}</p><p class="muted">Go back to the app and try again.</p>
<a class="btn" href="autoapply://auth">Back to Auto Apply</a></div>""")


def delete_confirm(email, token):
    return page(f"Delete your account - {APP_NAME}", f"""<div class="card"><h1>Delete your {e(APP_NAME)} account</h1>
<p>Signed in with Google as <b>{e(email)}</b>.</p>
<p class="muted">This deletes your account and everything our server keeps for it, right away: your CV, answers and jobs,
the activity the app reported and your AI usage records. It also disconnects Google Drive (the files in your Drive stay
yours). It cannot be undone.</p>
<form method="post" action="/delete-account/google"><input type="hidden" name="token" value="{e(token)}">
<button type="submit">Delete my account</button></form></div>""")
