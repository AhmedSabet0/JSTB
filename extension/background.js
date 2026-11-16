/* JSTB extension: signs in to your JSTB account, takes your matched jobs from the server and applies to them one
   by one, at your pace, in a background tab. */
import { answerFields, guessStartYear } from './answers.js';

const DEFAULT_SERVER = 'https://145-241-153-177.sslip.io';
const VERSION = chrome.runtime.getManifest().version;
const DEFAULTS = {
  server: DEFAULT_SERVER, token: '', user: null, running: false, message: 'Not started',
  queue: [], active: null, nextAt: 0, log: [], done: {}, times: [], events: [],
  settings: { perHour: 15, perDay: 40, minScore: 60, linkedin: true, wuzzuf: true, company: true, noSubmit: false },
  limits: { day: 0, hour: 0 }, cache: {}, auth: null, data: null, dataAt: 0, devDrivers: [],
};
const JOB_TIMEOUT = 6 * 60;       // seconds one application may take
const now = () => Date.now() / 1000;

// ------------------------------------------------------------------ state (kept in storage: the worker sleeps)
let chain = Promise.resolve();
async function load() {
  const s = await chrome.storage.local.get(Object.keys(DEFAULTS));
  const st = Object.assign({}, structuredClone(DEFAULTS), s);
  st.settings = Object.assign({}, DEFAULTS.settings, s.settings || {});
  return st;
}
/** Run fn(state) alone (no two changes at once) and save what it changed. */
function update(fn) {
  const run = chain.then(async () => {
    const st = await load();
    const out = await fn(st);
    await chrome.storage.local.set(st);
    return out;
  });
  chain = run.catch(() => {});
  return run;
}
function addLog(st, text) {
  st.log.push({ at: now(), text: String(text).slice(0, 400), sent: false });
  if (st.log.length > 300) st.log = st.log.slice(-300);
}

// ------------------------------------------------------------------ server
class ApiError extends Error {
  constructor(status, message, code) { super(message); this.status = status; this.code = code; }
}
async function api(st, path, body, method) {
  const headers = { 'Content-Type': 'application/json', 'X-App-Flavor': 'chrome', 'X-App-Version': '0' };
  if (st.token) headers.Authorization = 'Bearer ' + st.token;
  let resp;
  try {
    resp = await fetch(st.server.replace(/\/+$/, '') + path, {
      method: method || (body ? 'POST' : 'GET'), headers, body: body ? JSON.stringify(body) : undefined,
    });
  } catch (e) {
    throw new ApiError(0, 'Could not reach the JSTB server. Check your internet connection.', 'network');
  }
  let data = {};
  try { data = await resp.json(); } catch (e) { /* not JSON */ }
  if (!resp.ok) {
    const err = data.error || data.detail || {};
    throw new ApiError(resp.status, data.message || err.message || (typeof err === 'string' ? err : '') || 'Server error ' + resp.status,
      data.code || err.code || '');
  }
  return data;
}

// ------------------------------------------------------------------ sign in with Google (through the JSTB server)
function b64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
async function signIn() {
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(48)));
  const challenge = b64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
  const st = await load();
  const r = await api(st, '/v1/auth/google/start', { challenge, device: 'Chrome extension' });
  const tab = await chrome.tabs.create({ url: r.url });
  await update((s) => { s.auth = { verifier, tabId: tab.id, at: now(), tries: 0 }; });
  chrome.alarms.create('claim', { delayInMinutes: 0.5, periodInMinutes: 0.5 });
  claimLoop();
  return { ok: true };
}
let claiming = false;
async function claimLoop() {          // while the worker is awake: every 6 seconds; the alarm picks it up if it sleeps
  if (claiming) return;
  claiming = true;
  try {
    for (let i = 0; i < 4; i++) {
      await new Promise((r) => setTimeout(r, 6000));
      if (await claim()) return;
    }
  } finally {
    claiming = false;
  }
}
async function claim() {
  const st = await load();
  if (!st.auth) { chrome.alarms.clear('claim'); return true; }
  if (now() - st.auth.at > 300 || st.auth.tries >= 24) {
    await update((s) => { s.auth = null; s.message = 'Sign-in was not finished. Try again.'; });
    chrome.alarms.clear('claim');
    return true;
  }
  let r;
  try {
    r = await api(st, '/v1/auth/google/claim', { verifier: st.auth.verifier, device: 'Chrome extension' });
  } catch (e) {
    if (e.status === 400 || e.status === 403) {
      await update((s) => { s.auth = null; s.message = e.message; });
      return true;
    }
    return false;
  }
  if (r.pending) { await update((s) => { if (s.auth) s.auth.tries += 1; }); return false; }
  const tabId = st.auth.tabId;
  await update((s) => {
    s.auth = null; s.token = r.token; s.user = r.user; s.message = 'Signed in'; s.data = null; s.dataAt = 0;
    addLog(s, 'Signed in as ' + ((r.user && r.user.email) || 'your account'));
  });
  chrome.alarms.clear('claim');
  if (!(await chrome.storage.local.get('setup')).setup) chrome.runtime.openOptionsPage();      // your answers, once
  try { await chrome.tabs.remove(tabId); } catch (e) { /* already closed */ }
  return true;
}
async function signOut() {
  const st = await load();
  try { if (st.token) await api(st, '/v1/auth/logout', {}); } catch (e) { /* signed out here anyway */ }
  await stopRun('Signed out');
  await update((s) => { s.token = ''; s.user = null; s.data = null; s.queue = []; s.cache = {}; s.message = 'Signed out'; });
  await chrome.storage.local.remove('cv');
}

// ------------------------------------------------------------------ your data (profile, saved answers, CV)
async function userData(st, fresh) {
  if (!fresh && st.data && now() - st.dataAt < 900) return st.data;
  const data = { profile: {}, facts: [], about: '', cvText: '', keywords: '' };
  let since = 0;
  for (let page = 0; page < 5; page++) {
    const r = await api(st, '/v1/me/data?since=' + since);
    for (const rec of r.records || []) {
      const d = rec.d || {};
      if (rec.k === 'profile') data.profile[rec.id] = String(d.v || '');
      else if (rec.k === 'facts' && d.value) data.facts.push({ label: String(d.label || ''), value: String(d.value), asked: d.asked || [] });
      else if (rec.k === 'settings' && rec.id === 'ABOUT_ME') data.about = String(d.v || '');
    }
    if (!r.more) break;
    since = r.rev;
  }
  try {
    const cv = await api(st, '/v1/me/cv');
    data.cvText = String(cv.text || '').slice(0, 12000);
    const name = cv.name || 'CV.pdf';
    const lower = name.toLowerCase();
    const mime = lower.endsWith('.pdf') ? 'application/pdf' : lower.endsWith('.docx')
      ? 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' : 'application/msword';
    await chrome.storage.local.set({ cv: { name, mime, b64: cv.data || '' } });
  } catch (e) {
    if (e.status !== 404) throw e;
  }
  await update((s) => { s.data = data; s.dataAt = now(); });
  return data;
}

/** Your data with what you typed in "My answers" here on top of what the app saved. */
async function withSetup(data) {
  const setup = (await chrome.storage.local.get('setup')).setup || {};
  const profile = Object.assign({}, data.profile);
  for (const [k, v] of Object.entries(setup)) if (v !== '' && v !== null && v !== undefined) profile[k] = String(v);
  return Object.assign({}, data, { profile });
}

// ------------------------------------------------------------------ the run
function siteOf(st, url) {
  for (const [prefix, kind] of st.devDrivers || []) if (url.startsWith(prefix)) return kind;
  let host = '';
  try { host = new URL(url).hostname; } catch (e) { return ''; }
  if (/(^|\.)linkedin\.com$/.test(host)) return 'linkedin';
  if (/(^|\.)wuzzuf\.net$/.test(host)) return 'wuzzuf';
  return 'company';
}

function countSince(st, seconds) {
  const from = now() - seconds;
  return st.times.filter((t) => t >= from).length;
}
function caps(st) {
  const perDay = st.limits.day > 0 ? Math.min(st.settings.perDay, st.limits.day) : st.settings.perDay;
  const perHour = st.limits.hour > 0 ? Math.min(st.settings.perHour, st.limits.hour) : st.settings.perHour;
  return { perDay: Math.max(1, perDay), perHour: Math.max(1, perHour) };
}

async function startRun() {
  const st = await load();
  if (!st.token) return { ok: false, message: 'Sign in first.' };
  try {
    const data = await userData(st, true);
    if (!data.cvText) return { ok: false, message: 'Add your CV in the JSTB app first: the answers come from it.' };
  } catch (e) {
    if (e.code === 'signed_out') await update((s) => { s.token = ''; s.user = null; });
    return { ok: false, message: e.message };
  }
  await update((s) => {
    s.running = true; s.nextAt = 0; s.message = 'Starting'; s.queue = [];
    for (const [id, v] of Object.entries(s.done)) if (v === 'stuck' || v === 'error') delete s.done[id];     // one more try
    addLog(s, 'Started (' + caps(s).perHour + ' per hour, up to ' + caps(s).perDay + ' a day)');
  });
  chrome.alarms.create('tick', { delayInMinutes: 0.5, periodInMinutes: 0.5 });
  await report(true);
  tick();
  return { ok: true };
}

async function stopRun(why) {
  const st = await load();
  const tabId = st.active && st.active.tabId;
  await update((s) => {
    if (s.running) addLog(s, 'Stopped' + (why ? ': ' + why : ''));
    s.running = false; s.active = null; s.message = why || 'Stopped';
  });
  chrome.alarms.clear('tick');
  if (tabId) { try { await chrome.tabs.remove(tabId); } catch (e) { /* already closed */ } }
  await report(false);
}

async function refill(st) {
  const r = await api(st, '/v1/jobs/matched?min_score=' + encodeURIComponent(st.settings.minScore) + '&status=new');
  const jobs = (r.jobs || []).filter((j) => j.url && !st.done[j.id]).filter((j) => {
    const kind = siteOf(st, j.url);
    return kind === 'linkedin' ? (st.settings.linkedin || st.settings.company) : !!st.settings[kind];
  });
  return jobs.map((j) => ({ id: j.id, title: j.title || '', company: j.company || '', url: j.url, score: j.score || 0 }));
}

let ticking = false;
async function tick() {
  if (ticking) return;
  ticking = true;
  try {
    let st = await load();
    if (!st.running) { chrome.alarms.clear('tick'); return; }
    if (st.active) {
      if (now() - st.active.startedAt > JOB_TIMEOUT) await finish(st.active.tabId, { status: 'stuck', reason: 'Took too long' });
      return;
    }
    if (now() < st.nextAt) return;
    const c = caps(st);
    if (countSince(st, 86400) >= c.perDay) return void await stopRun('Reached your limit for today (' + c.perDay + ')');
    if (countSince(st, 3600) >= c.perHour) {
      const oldest = Math.min(...st.times.filter((t) => t >= now() - 3600));
      return void await update((s) => { s.nextAt = oldest + 3600 + 5; s.message = 'Waiting: ' + c.perHour + ' sent in the last hour'; });
    }
    if (!st.queue.length) {
      let jobs;
      try {
        jobs = await refill(st);
      } catch (e) {
        if (e.code === 'signed_out') { await update((s) => { s.token = ''; s.user = null; }); return void await stopRun(e.message); }
        return void await update((s) => { s.nextAt = now() + 120; s.message = e.message; });
      }
      if (!jobs.length) {
        return void await update((s) => { s.nextAt = now() + 600; s.message = 'No new matched jobs. Checking again in 10 minutes.'; });
      }
      await update((s) => { s.queue = jobs; addLog(s, jobs.length + ' matched jobs to apply to'); });
      st = await load();
    }
    const job = st.queue[0];
    const tab = await chrome.tabs.create({ url: job.url, active: false });
    await update((s) => {
      s.queue = s.queue.slice(1);
      s.active = { job, tabId: tab.id, startedAt: now(), steps: 0, kind: siteOf(s, job.url), submitting: false, external: 0 };
      s.message = 'Applying: ' + job.title + (job.company ? ' at ' + job.company : '');
      addLog(s, s.message);
    });
  } finally {
    ticking = false;
  }
}

async function inject(tabId) {
  try {
    for (let i = 0; ; i++) {        // a page that redirects right away refuses the first try
      try {
        await chrome.scripting.executeScript({ target: { tabId }, files: ['content/form.js', 'content/drivers.js'] });
        return;
      } catch (e) {
        if (i >= 4) throw e;
        await new Promise((r) => setTimeout(r, 2500));
        const tab = await chrome.tabs.get(tabId);
        if (tab.status !== 'complete') return;      // still loading: the next "complete" starts it
      }
    }
  } catch (e) {
    const st = await load();
    if (st.active && st.active.tabId === tabId) await finish(tabId, { status: 'error', reason: 'Could not work on this page' });
  }
}

/** One application ended: record it, tell the server, plan the next one. */
async function finish(tabId, res) {
  const st = await load();
  const a = st.active;
  if (!a || a.tabId !== tabId) return;
  const status = res.status;
  if (status === 'external' && res.url && a.external < 2) {       // LinkedIn sends this job to the company's site
    await update((s) => { s.active.kind = 'company'; s.active.external += 1; s.active.steps = 0; addLog(s, '  Applies on the company site'); });
    return void await chrome.tabs.update(tabId, { url: res.url });
  }
  const job = a.job;
  const channel = a.kind === 'linkedin' ? 'linkedin' : 'company';
  const c = caps(st);
  const gap = Math.round(3600 / c.perHour);
  await update((s) => {
    s.active = null;
    const kept = { applied: 'applied', stuck: 'stuck', error: 'error', needs_you: 'needs_answer' }[status];
    if (status === 'applied') {
      s.times.push(now());
      s.times = s.times.filter((t) => t >= now() - 86400 * 2);
    }
    s.done[job.id] = status;
    const ids = Object.keys(s.done);
    if (ids.length > 3000) for (const k of ids.slice(0, ids.length - 3000)) delete s.done[k];
    if (kept) {
      s.events.push({ job_id: job.id, title: job.title, company: job.company, status: kept, at: now(), channel,
        reason: String(res.reason || '').slice(0, 300), url: job.url });
    }
    const label = { applied: 'Applied', stuck: 'Stuck', error: 'Error', needs_you: 'Needs you', skipped: 'Skipped',
      dry_run: 'Test run', daily_limit: 'LinkedIn limit', ai_down: 'No AI' }[status] || status;
    addLog(s, '  ' + label + (res.reason ? ': ' + res.reason : '') + (res.qa && res.qa.length ? ' (' + res.qa.length + ' answers)' : ''));
    s.nextAt = now() + (status === 'applied' || status === 'dry_run' ? gap : 20);
    s.message = status === 'applied' ? 'Applied. Next one in about ' + Math.max(1, Math.round(gap / 60)) + ' min' : 'Next job soon';
  });
  if (!res.keepTab) { try { await chrome.tabs.remove(tabId); } catch (e) { /* already closed */ } }
  try {
    if (status === 'applied') await api(st, '/v1/jobs/match-action', { id: job.id, action: 'applied' });
    else if (status === 'skipped') await api(st, '/v1/jobs/match-action', { id: job.id, action: 'skipped' });
  } catch (e) { /* the report below still records it */ }
  if (status === 'ai_down') return void await update((s) => { delete s.done[job.id]; });
  if (status === 'daily_limit') {
    await update((s) => { delete s.done[job.id]; });
    return void await stopRun("LinkedIn's daily Easy Apply limit is reached. Start again tomorrow.");
  }
  await report(true);
}

async function report(running) {
  const st = await load();
  if (!st.token) return;
  const lines = st.log.filter((l) => !l.sent).map((l) => ({ at: l.at, text: '[Chrome] ' + l.text }));
  try {
    const r = await api(st, '/v1/report', {
      running: !!(running && st.running), mode: 'auto', message: st.message, events: st.events, log: lines,
      device: 'Chrome extension', app_version: 'ext ' + VERSION,
    });
    await update((s) => {
      s.events = s.events.slice(st.events.length);
      for (const l of s.log) if (l.at <= (lines.length ? lines[lines.length - 1].at : 0)) l.sent = true;
      s.limits = { day: r.max_per_day || 0, hour: r.max_per_hour || 0 };
    });
    if (r.stop && st.running) await stopRun(r.reason || 'Stopped by the server');
  } catch (e) {
    if (e.code === 'signed_out') await update((s) => { s.token = ''; s.user = null; });
  }
}

// ------------------------------------------------------------------ events
chrome.alarms.onAlarm.addListener((al) => {
  if (al.name === 'tick') tick();
  if (al.name === 'claim') claim();
});

chrome.tabs.onUpdated.addListener(async (tabId, info, tab) => {
  if (info.status !== 'complete') return;
  const st = await load();
  if (st.auth && st.auth.tabId === tabId && /\/oauth\/google\/callback/.test(tab.url || '')) return void claim();
  if (st.active && st.active.tabId === tabId && /^https?:/.test(tab.url || '')) {
    if (st.active.kind === 'linkedin' && siteOf(st, tab.url) !== 'linkedin') await update((s) => { if (s.active) s.active.kind = 'company'; });
    inject(tabId);
  }
});

chrome.tabs.onCreated.addListener(async (tab) => {     // Apply on LinkedIn opened the company's site in a new tab
  const st = await load();
  if (!st.active || tab.openerTabId !== st.active.tabId || !st.active.expectExternal) return;
  const old = st.active.tabId;
  await update((s) => { s.active.tabId = tab.id; s.active.kind = 'company'; s.active.steps = 0; s.active.expectExternal = false;
    addLog(s, '  Applies on the company site'); });
  try { await chrome.tabs.remove(old); } catch (e) { /* already closed */ }
});

chrome.tabs.onRemoved.addListener(async (tabId) => {
  const st = await load();
  if (st.active && st.active.tabId === tabId) await finish(tabId, { status: 'error', reason: 'The tab was closed' });
});

async function onMessage(msg, sender) {
  const own = String(sender.url || '').startsWith(chrome.runtime.getURL(''));     // the popup, not a job page
  const tabId = own ? undefined : (sender.tab && sender.tab.id);
  if (!own && tabId === undefined) return null;
  if (!own) {        // from the page of the job being applied to
    const st = await load();
    const a = st.active;
    if (!a || a.tabId !== tabId) return null;
    if (msg.t === 'hello') {
      return { job: a.job, kind: a.kind, steps: a.steps, noSubmit: st.settings.noSubmit,
        sites: { linkedin: st.settings.linkedin, wuzzuf: st.settings.wuzzuf, company: st.settings.company } };
    }
    if (msg.t === 'answers') {
      try {
        const data = await withSetup(await userData(st));
        const out = await answerFields(msg.fields || [], msg.ctx || {}, data, st.user, st.cache,
          (messages, maxTokens) => api(st, '/v1/ai/chat', { messages, purpose: 'apply', max_tokens: maxTokens, temperature: 0.2 }));
        if (out.stats && out.stats.questions) {
          const x = out.stats;
          await update((s) => { addLog(s, '  ' + x.questions + ' questions: ' + x.own + ' from your answers, ' + x.aiGot + '/' + x.ai
            + ' from the AI' + (x.note ? ' (' + x.note + ')' : '')); });
        }
        if (Object.keys(out.learned).length) {
          await update((s) => {
            Object.assign(s.cache, out.learned);
            const keys = Object.keys(s.cache);
            if (keys.length > 1500) for (const k of keys.slice(0, keys.length - 1500)) delete s.cache[k];
          });
        }
        return { answers: out.answers };
      } catch (e) {
        await update((s) => { addLog(s, '  AI: ' + e.message); });
        const down = e.status === 404 || e.status >= 500 || e.status === 0
          || ['not_active', 'ai_limit', 'ai_not_configured', 'bad_model', 'upstream_auth'].includes(e.code);
        if (down) {      // no AI, no answers: stop here and keep this job for the next run
          const why = e.status === 404 ? 'The server has no AI service at ' + st.server + '/v1/ai/chat (it answered "Not Found").' : e.message;
          await finish(tabId, { status: 'ai_down', reason: why });
          await stopRun(why);
        }
        return { answers: {} };
      }
    }
    if (msg.t === 'cv') return (await chrome.storage.local.get('cv')).cv || null;
    if (msg.t === 'log') return void await update((s) => { addLog(s, '  ' + msg.text); });
    if (msg.t === 'step') return await update((s) => { if (s.active) s.active.steps += 1; return s.active ? s.active.steps : 0; });
    if (msg.t === 'submitting') return void await update((s) => { if (s.active) { s.active.submitting = true; s.active.steps += 1; } });
    if (msg.t === 'expect_external') return void await update((s) => { if (s.active) s.active.expectExternal = true; });
    if (msg.t === 'result') return void await finish(tabId, msg);
    return null;
  }
  // from the popup
  if (msg.t === 'state') {
    const st = await load();
    const c = caps(st);
    return { signedIn: !!st.token, user: st.user, running: st.running, message: st.message, settings: st.settings, server: st.server,
      signingIn: !!st.auth, today: countSince(st, 86400), hour: countSince(st, 3600), caps: c, queue: st.queue.length,
      active: st.active ? st.active.job : null, nextAt: st.nextAt, log: st.log.slice(-60) };
  }
  if (msg.t === 'signin') { try { return await signIn(); } catch (e) { return { ok: false, message: e.message }; } }
  if (msg.t === 'signout') { await signOut(); return { ok: true }; }
  if (msg.t === 'start') return startRun();
  if (msg.t === 'stop') { await stopRun('Stopped by you'); return { ok: true }; }
  if (msg.t === 'settings') {
    return update((s) => {
      const n = msg.settings || {};
      const num = (v, lo, hi, d) => { const x = parseInt(v, 10); return Number.isFinite(x) ? Math.min(hi, Math.max(lo, x)) : d; };
      s.settings = {
        perHour: num(n.perHour, 1, 30, s.settings.perHour), perDay: num(n.perDay, 1, 200, s.settings.perDay),
        minScore: num(n.minScore, 0, 100, s.settings.minScore), linkedin: !!n.linkedin, wuzzuf: !!n.wuzzuf, company: !!n.company,
        noSubmit: !!n.noSubmit,
      };
      s.queue = [];
      return { ok: true };
    });
  }
  if (msg.t === 'server') {
    return update((s) => {
      const url = String(msg.server || '').trim().replace(/\/+$/, '');
      if (!/^https?:\/\/[^\s/]+/.test(url)) return { ok: false, message: 'Enter the server address (https://...)' };
      if (url !== s.server) { s.server = url; s.token = ''; s.user = null; s.data = null; s.queue = []; }
      return { ok: true };
    });
  }
  if (msg.t === 'get_setup') {
    const st = await load();
    let data = { profile: {}, cvText: '' };
    try { if (st.token) data = await userData(st); } catch (e) { /* show what is saved here */ }
    const merged = await withSetup(data);
    return { profile: merged.profile, guessYear: guessStartYear(data.cvText), signedIn: !!st.token };
  }
  if (msg.t === 'save_setup') { await chrome.storage.local.set({ setup: msg.setup || {} }); await update((s) => { s.cache = {}; }); return { ok: true }; }
  if (msg.t === 'clear_log') return update((s) => { s.log = []; return { ok: true }; });
  return null;
}

chrome.runtime.onMessage.addListener((msg, sender, respond) => {
  onMessage(msg, sender).then((r) => respond(r === undefined ? null : r), (e) => respond({ ok: false, message: String(e && e.message) }));
  return true;
});

chrome.runtime.onStartup.addListener(async () => {
  const st = await load();
  if (st.running) {
    await update((s) => { s.active = null; });
    chrome.alarms.create('tick', { delayInMinutes: 0.5, periodInMinutes: 0.5 });
  }
});
