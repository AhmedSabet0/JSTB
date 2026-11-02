/* Auto Apply (Android): setup, home, settings. Talks to the app through window.AA. */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // The app answers through window.__aaDone, so a slow request never freezes the screen.
  const pendingCalls = new Map();
  let callSeq = 0;
  window.__aaDone = (id, raw) => {
    const done = pendingCalls.get(id);
    if (done) { pendingCalls.delete(id); done(raw); }
  };
  function bridge(path, body) {
    if (!window.AA.call) return Promise.resolve(window.AA.api(path, body));
    return new Promise((resolve) => {
      const id = String(++callSeq);
      pendingCalls.set(id, resolve);
      window.AA.call(id, path, body);
    });
  }
  async function api(path, body) {
    if (!window.AA) throw new Error('The app is not ready');
    const raw = await bridge(path, body === undefined ? '' : JSON.stringify(body));
    let data = {};
    try { data = JSON.parse(raw); } catch (e) { /* not json */ }
    if (data.ok === false) throw new Error(data.error || 'Request failed');
    return data;
  }

  let toastTimer = null;
  function toast(msg, bad = false) {
    const t = $('toast');
    t.textContent = msg;
    t.className = `toast${bad ? ' bad' : ''}`;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, bad ? 6000 : 3000);
  }
  function store(key, value) {
    try {
      if (value === undefined) return localStorage.getItem(key);
      localStorage.setItem(key, value);
    } catch (e) { /* storage unavailable */ }
    return null;
  }

  const fmtTime = (ts) => new Date(ts * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const fmtWhen = (ts) => {
    const d = new Date(ts * 1000);
    return new Date().toDateString() === d.toDateString() ? fmtTime(ts)
      : d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  };
  const fmtDur = (sec) => {
    sec = Math.max(0, Math.round(sec));
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = sec % 60;
    return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
  };
  const plural = (n, one, many) => `${n} ${n === 1 ? one : (many || `${one}s`)}`;
  // Same count as the app: whole years since July of the graduation year.
  function yearsSince(y) {
    const year = Number(y);
    if (!/^\d{4}$/.test(String(y || '')) || year < 1950) return -1;
    const now = new Date();
    const start = new Date(now.getFullYear(), 0, 1);
    const t = now.getFullYear() + Math.floor((now - start) / 86400000) / 366;
    return Math.max(0, Math.floor(t - (year + 0.5)));
  }

  // ------------------------------------------------------------------ navigation
  const TITLES = { setup: 'Auto Apply', home: 'Auto Apply', settings: 'Settings', info: 'Your info', jobs: 'All jobs',
    log: 'Activity' };
  let view = null;
  const history = [];
  function go(v, opts = {}) {
    if (view && view !== v && !opts.back && !['setup'].includes(v)) history.push(view);
    if (v === 'home' || v === 'setup') history.length = 0;
    view = v;
    document.querySelectorAll('.view').forEach((s) => { s.hidden = s.id !== `v-${v}`; });
    $('bar-title').textContent = TITLES[v] || 'Auto Apply';
    const top = v === 'home' || v === 'setup';
    $('nav-back').hidden = top;
    $('nav-settings').hidden = v !== 'home';
    document.querySelector('.logo').style.display = top ? '' : 'none';
    window.scrollTo(0, 0);
    if (v === 'settings') loadSettings();
    if (v === 'info') loadInfo();
    if (v === 'jobs') loadJobs();
    if (v === 'log') { const box = $('log'); box.scrollTop = box.scrollHeight; window.scrollTo(0, document.body.scrollHeight); }
    renderBar();
    renderQuestionBar();
  }
  function back() {
    if (view === 'setup') {
      const i = W_STEPS.indexOf(wStep);
      if (i > 0 && wStep !== 'welcome') { wShow(W_STEPS[i - 1]); return true; }
      if (state && state.setup.setup_done) { go('home'); return true; }
      return false;
    }
    if (view === 'home') return false;
    go(history.pop() || 'home', { back: true });
    return true;
  }
  window.aaBack = () => back();
  $('nav-back').addEventListener('click', () => back());
  $('nav-settings').addEventListener('click', () => go('settings'));
  document.querySelectorAll('[data-go]').forEach((b) => b.addEventListener('click', () => go(b.dataset.go)));
  document.querySelectorAll('[data-share]').forEach((b) => b.addEventListener('click',
    () => api('/api/share', { what: b.dataset.share }).catch((e) => toast(e.message, true))));
  document.querySelectorAll('[data-signin]').forEach((b) => b.addEventListener('click',
    () => api('/api/linkedin', {}).catch((e) => toast(e.message, true))));
  document.querySelectorAll('[data-perm]').forEach((b) => b.addEventListener('click',
    () => api('/api/permission', { which: b.dataset.perm }).catch((e) => toast(e.message, true))));
  window.addEventListener('scroll', () => document.querySelector('.appbar').classList.toggle('scrolled', window.scrollY > 4),
    { passive: true });

  // ------------------------------------------------------------------ state
  let state = null;
  let firstState = true;
  async function refresh() {
    try {
      state = await api('/api/state');
    } catch (e) {
      return;
    }
    if (firstState) {
      firstState = false;
      if (!state.setup.setup_done) startSetup(); else go('home');
    }
    renderHome();
    renderBar();
    renderQuestion(state.running ? state.question : null);
    renderUpdate(state.update, state.running);
    if (view === 'setup') wLive();
    if (view === 'settings') settingsLive();
    const n = state.open_questions || 0;
    $('open-badge').hidden = !n;
    $('open-badge').textContent = n;
    if (n !== lastOpenCount) {
      lastOpenCount = n;
      if (view === 'info' && !$('open-list').contains(document.activeElement)) loadOpen();
    }
    $('info-sub').textContent = state.facts ? `${plural(state.facts, 'answer')} you can reuse, by category` : 'What forms ask about you, by category';
  }
  let lastAccCheck = Date.now();
  async function loop() {
    await refresh();
    if (state && state.setup.account && !acc().pro && !document.hidden && Date.now() - lastAccCheck > 60000) {
      lastAccCheck = Date.now();
      api('/api/account/refresh', {}).catch(() => {});
    }
    setTimeout(loop, document.hidden ? 20000 : 1000);  // the app is in the background: barely poll
  }

  function renderBar() {
    const el = $('bar-state');
    if (!state || view !== 'home' || !state.running) { el.hidden = true; return; }
    const st = state.status || {};
    const wait = ['waiting', 'signin', 'question'].includes(st.state);
    el.hidden = false;
    el.className = `bar-state${wait ? ' wait' : ''}`;
    el.textContent = st.state === 'question' ? 'Question' : st.state === 'signin' ? 'Sign in' : wait ? 'Waiting'
      : state.options && state.options.no_submit ? 'Test run' : 'Applying';
  }

  // ------------------------------------------------------------------ setup wizard
  const W_STEPS = ['welcome', 'account', 'cv', 'answers', 'jobs', 'linkedin', 'done'];
  const W_PARTS = ['account', 'cv', 'answers', 'jobs', 'linkedin'];
  let wStep = null;
  let profileData = null;   // GET /api/profile
  let suggest = null;       // read from the CV
  let suggesting = false;

  function wFirst(setup) {
    if (!setup.account && !setup.has_cv && !setup.profile_done) return 'welcome';
    if (!setup.account) return 'account';
    if (!setup.has_cv) return 'cv';
    if (!setup.profile_done) return 'answers';
    if (!setup.has_search) return 'jobs';
    if (!setup.signed_in) return 'linkedin';
    return 'done';
  }
  async function startSetup(step) {
    go('setup');
    await loadProfile();
    wShow(step || wFirst(state.setup));
    kickSuggest(false);
  }

  function wShow(step) {
    wStep = step;
    document.querySelectorAll('.step').forEach((s) => { s.hidden = s.dataset.step !== step; });
    const at = W_PARTS.indexOf(step);
    $('w-progress').hidden = at < 0;
    $('w-progress').innerHTML = W_PARTS.map((p, i) => `<i class="${i < at ? 'done' : i === at ? 'now' : ''}"></i>`).join('');
    if (step === 'account') wAccState();
    if (step === 'cv') cvStates();
    if (step === 'answers') {
      renderProfileForm($('w-answers'), true);
      wSugState();
    }
    if (step === 'jobs') wJobs();
    wLive();
    window.scrollTo(0, 0);
  }

  // Parts of the wizard that follow the app's state (sign-in, permissions, CV).
  function wLive() {
    if (!state || view !== 'setup') return;
    const s = state.setup;
    const next = $('w-next');
    const backBtn = $('w-back');
    backBtn.hidden = wStep === 'welcome';
    backBtn.textContent = 'Back';
    const labels = { welcome: 'Get started', account: accLabel(), cv: 'Continue', answers: 'Save and continue',
      jobs: 'Continue', linkedin: s.signed_in ? 'Continue' : 'Skip for now', done: 'Start applying' };
    if (!next.dataset.busy) next.textContent = labels[wStep] || 'Continue';
    next.disabled = (wStep === 'cv' && !s.has_cv) || !!next.dataset.busy;
    if (wStep === 'linkedin') {
      $('w-li-ok').hidden = !s.signed_in;
      $('w-li-btn').hidden = !!s.signed_in;
    }
    if (wStep === 'done') permStates(document.querySelector('.step[data-step="done"]'));
    if (wStep === 'cv') cvStates();
  }

  async function wNext() {
    const next = $('w-next');
    if (next.disabled) return;
    try {
      if (wStep === 'welcome') wShow('account');
      else if (wStep === 'account') await wAccount();
      else if (wStep === 'cv') wShow('answers');
      else if (wStep === 'answers') { if (await saveProfile($('w-answers'))) wShow('jobs'); }
      else if (wStep === 'jobs') { if (await wSaveJobs()) wShow('linkedin'); }
      else if (wStep === 'linkedin') wShow('done');
      else if (wStep === 'done') await finishSetup(true);
    } catch (e) {
      toast(e.message, true);
    }
  }
  $('w-next').addEventListener('click', wNext);
  $('w-back').addEventListener('click', () => back());

  function busy(btn, text) {
    if (text) { btn.dataset.busy = '1'; btn.textContent = text; btn.disabled = true; } else { delete btn.dataset.busy; btn.disabled = false; }
    wLive();
  }

  // ------------------------------------------------------------------ your account (first step)
  let accMode = 'signup';
  let accInfoAsked = false;
  const acc = () => (state && state.account) || {};
  function accLabel() {
    if (acc().signed_in) return 'Continue';
    return accMode === 'signup' ? 'Create account' : 'Sign in';
  }
  function setAccMode(mode) {
    accMode = mode;
    document.querySelectorAll('[data-accmode]').forEach((b) => b.classList.toggle('on', b.dataset.accmode === mode));
    $('w-acc-name-field').hidden = mode !== 'signup';
    $('w-acc-pw').autocomplete = mode === 'signup' ? 'new-password' : 'current-password';
    $('w-acc-pw-hint').hidden = mode !== 'signup';
    $('w-acc-title').textContent = mode === 'signup' ? 'Create your account' : 'Sign in';
    $('w-acc-err').hidden = true;
    accHelp();
    wLive();
  }
  document.querySelectorAll('[data-accmode]').forEach((b) => b.addEventListener('click', () => setAccMode(b.dataset.accmode)));
  function accHelp() {
    const c = acc().contact;
    $('w-acc-help').hidden = !(c && accMode === 'login');
    if (c) $('w-acc-help').innerHTML = `Forgot your password? Email <a href="mailto:${esc(c)}">${esc(c)}</a>.`;
  }
  function wAccState() {
    const a = acc();
    $('w-acc-ok').hidden = !a.signed_in;
    $('w-acc-form').hidden = !!a.signed_in;
    $('w-acc-who').textContent = a.email || '';
    $('w-acc-lead').textContent = a.signed_in ? 'You are signed in. Continue to add your CV.'
      : 'Your account connects the AI that reads jobs and fills in forms. No AI key needed.';
    if (a.signed_in) { $('w-acc-title').textContent = 'Your account'; return; }
    $('w-acc-server-field').hidden = !!a.server_built_in;
    if (!a.server_built_in && !$('w-acc-server').value) $('w-acc-server').value = (a.server || '').replace(/^https:\/\//, '');
    if (a.email && !$('w-acc-email').value) { $('w-acc-email').value = a.email; setAccMode('login'); } else setAccMode(accMode);
    if (!accInfoAsked && (a.server_built_in || a.server)) { accInfoAsked = true; loadAccInfo(); }
  }
  async function loadAccInfo() {
    try {
      const r = await api('/api/account/info', {});
      state.account = r.account;
      if (r.server && r.server.signups_open === false) {
        $('w-acc-note').textContent = 'New accounts are closed right now. You can still sign in.';
        $('w-acc-note').hidden = false;
        setAccMode('login');
      }
      accHelp();
    } catch (e) { /* offline: the form still works */ }
  }
  async function wAccount() {
    const a = acc();
    if (a.signed_in) { wShow('cv'); kickSuggest(false); return; }
    const err = $('w-acc-err');
    err.hidden = true;
    const body = { email: $('w-acc-email').value.trim(), password: $('w-acc-pw').value };
    if (accMode === 'signup') body.name = $('w-acc-name').value.trim();
    if (!a.server_built_in) body.server = $('w-acc-server').value.trim();
    let problem = '';
    if (!a.server_built_in && !body.server) problem = 'Type the server address.';
    else if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(body.email)) problem = 'Type your email address.';
    else if (accMode === 'signup' && body.password.length < 8) problem = 'Use a password of at least 8 characters.';
    else if (!body.password) problem = 'Type your password.';
    if (problem) { err.textContent = problem; err.hidden = false; return; }
    busy($('w-next'), accMode === 'signup' ? 'Creating your account…' : 'Signing in…');
    try {
      await api(`/api/account/${accMode}`, body);
      $('w-acc-pw').value = '';
      await refresh();
      busy($('w-next'));
      if (state.setup.setup_done) { go('home'); toast('Signed in.'); return; }
      wShow('cv');
      kickSuggest(false);
    } catch (e) {
      busy($('w-next'));
      if (/already exists/i.test(e.message)) setAccMode('login');
      err.textContent = e.message;
      err.hidden = false;
    }
  }
  ['w-acc-email', 'w-acc-pw', 'w-acc-name', 'w-acc-server'].forEach((id) => $(id).addEventListener('keydown', (e) => {
    if (e.key === 'Enter') wNext();
  }));

  async function wSaveJobs() {
    const titles = wTitleEditor.get();
    if (!titles.length) { toast('Add at least one job title.', true); return false; }
    await api('/api/settings', { KEYWORDS: titles.join('\n'), LOCATION: $('w-location').value.trim() });
    await refresh();
    return true;
  }

  async function finishSetup(start) {
    await api('/api/setup', { done: true });
    if (start) {
      busy($('w-next'), 'Starting…');
      try {
        await startBot('apply');
      } finally {
        busy($('w-next'));
      }
    }
    await refresh();
    go('home');
  }

  // Read phone, city, graduation year, English and job titles from the CV once (AI).
  async function kickSuggest(force) {
    if (suggesting || !state || !state.setup.has_cv) return;
    if (suggest && !force) return;
    suggesting = true;
    wSugState();
    try {
      const r = await api('/api/profile/suggest', { force: !!force });
      suggest = r.suggest || null;
      if (r.problem) suggestProblem = r.problem;
    } catch (e) {
      suggestProblem = e.message;
    } finally {
      suggesting = false;
    }
    wSugState();
    if (view === 'setup' && wStep === 'answers') fillSuggestions($('w-answers'));
    if (view === 'setup' && wStep === 'jobs') wJobs();
  }
  let suggestProblem = '';
  function wSugState() {
    const el = $('w-sug-state');
    if (suggesting) { el.hidden = false; el.textContent = 'Reading your CV to fill in what it can…'; return; }
    el.hidden = !suggest;
    if (suggest) el.textContent = 'Answers marked “from your CV” were filled in by the AI. Check them.';
  }

  // ------------------------------------------------------------------ the setup answers form
  const PF = [
    { k: 'phone', label: 'Mobile number', kind: 'tel', ph: '01012345678', req: true },
    { k: 'city', label: 'City you live in', kind: 'text', ph: 'Cairo, Egypt' },
    { k: 'graduation_year', label: 'Year you graduated', kind: 'year', ph: '2019', req: true },
    { k: 'expected_salary', label: 'Expected monthly salary', kind: 'money', ph: '30000', req: true },
    { k: 'current_salary', label: 'Current monthly salary', kind: 'money', ph: '20000' },
    { k: 'notice_days', label: 'Notice period', kind: 'days', req: true },
    { k: 'military', label: 'Military service', kind: 'pick', options: ['Completed', 'Exempted', 'Postponed', 'Not applicable'] },
    { k: 'english', label: 'English', kind: 'pick', options: ['Native', 'Fluent', 'Professional', 'Good', 'Basic'] },
    { k: 'onsite', label: 'OK to work from the office?', kind: 'yesno' },
    { k: 'relocate', label: 'Willing to move to another city?', kind: 'yesno' },
    { k: 'driving', label: 'Driving license?', kind: 'yesno' },
    { k: 'authorized', label: 'Countries you can work in without a visa', kind: 'text', ph: 'Egypt' },
    { k: 'sponsorship', label: 'Need a visa sponsor?', kind: 'yesno',
      help: 'Used when a form does not name the country. For the countries above the app answers No, for others Yes.' },
  ];
  const DEFAULTS = { onsite: 'Yes', sponsorship: 'No', authorized: 'Egypt', currency: 'EGP' };
  const NOTICE_CHOICES = [['0', 'Immediately'], ['15', '15 days'], ['30', '30 days'], ['60', '60 days'], ['90', '90 days']];

  async function loadProfile() {
    try {
      profileData = await api('/api/profile');
      if (profileData.suggest) suggest = profileData.suggest;
    } catch (e) { toast(e.message, true); }
  }

  function renderProfileForm(box, wizard) {
    const saved = (profileData && profileData.profile) || {};
    const done = !!saved.done;
    const currencies = (profileData && profileData.currencies) || ['EGP', 'USD', 'EUR', 'SAR', 'AED'];
    box.innerHTML = PF.map((f) => {
      let control = '';
      if (f.kind === 'tel' || f.kind === 'text') {
        control = `<input type="${f.kind === 'tel' ? 'tel' : 'text'}" data-k="${f.k}" placeholder="${esc(f.ph)}" autocomplete="off">`;
      } else if (f.kind === 'year') {
        control = `<input type="number" inputmode="numeric" data-k="${f.k}" placeholder="${esc(f.ph)}" min="1960" max="2035">
          <p class="pf-help" data-years></p>`;
      } else if (f.kind === 'money') {
        const first = f.k === 'expected_salary';
        control = `<div class="money"><input type="number" inputmode="numeric" data-k="${f.k}" placeholder="${esc(f.ph)}">${first
          ? `<select data-k="currency" aria-label="Currency">${currencies.map((c) => `<option>${c}</option>`).join('')}</select>`
          : '<span class="cur-label" aria-hidden="true">EGP</span>'}</div>`;
      } else if (f.kind === 'days') {
        control = `<div class="pick-row" data-days>${NOTICE_CHOICES.map(([v, l]) => `<button type="button" class="pick" data-v="${v}">${l}</button>`).join('')}</div>
          <div class="pf-days"><input type="number" inputmode="numeric" data-k="${f.k}" min="0" max="365" placeholder="Other"><span class="muted">days</span></div>`;
      } else if (f.kind === 'pick') {
        control = `<div class="pick-row" data-pick="${f.k}">${f.options.map((o) => `<button type="button" class="pick" data-v="${esc(o)}">${esc(o)}</button>`).join('')}</div>`;
      } else if (f.kind === 'yesno') {
        control = `<div class="seg" data-pick="${f.k}"><button type="button" data-v="Yes">Yes</button><button type="button" data-v="No">No</button></div>`;
      }
      return `<div class="pf" data-f="${f.k}"><div class="pf-label"><span>${esc(f.label)}<span class="sug" hidden>from your CV</span></span>${
        f.req ? '<span class="req">needed</span>' : ''}</div>${control}${f.help ? `<p class="pf-help">${esc(f.help)}</p>` : ''}</div>`;
    }).join('') + rulesBox();
    // values: saved, else read from the CV, else defaults
    for (const f of PF) {
      let v = saved[f.k] || '';
      if (!v && !done && DEFAULTS[f.k]) v = DEFAULTS[f.k];
      setPf(box, f.k, v);
    }
    setPf(box, 'currency', saved.currency || 'EGP');
    fillSuggestions(box);
    box.querySelectorAll('[data-pick] button').forEach((b) => b.addEventListener('click', () => {
      const group = b.closest('[data-pick]');
      const on = !b.classList.contains('on');
      group.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b && on));
      b.closest('.pf').classList.remove('missing');
    }));
    box.querySelectorAll('[data-days] .pick').forEach((b) => b.addEventListener('click', () => {
      box.querySelector('[data-k="notice_days"]').value = b.dataset.v;
      paintDays(box);
      b.closest('.pf').classList.remove('missing');
    }));
    box.querySelector('[data-k="notice_days"]').addEventListener('input', () => paintDays(box));
    box.querySelector('[data-k="graduation_year"]').addEventListener('input', () => paintYears(box));
    box.querySelector('[data-k="currency"]').addEventListener('change', () => paintCurrency(box));
    box.querySelectorAll('input').forEach((i) => i.addEventListener('input', () => {
      i.closest('.pf').classList.remove('missing');
      const tag = i.closest('.pf').querySelector('.sug');
      if (tag) tag.hidden = true;
    }));
    paintDays(box);
    paintYears(box);
    paintCurrency(box);
    if (!wizard) box.querySelectorAll('.req').forEach((r) => { r.hidden = true; });
  }

  function rulesBox() {
    return `<details class="rules"><summary>How the app answers questions</summary><ul>
      <li>Years of experience with something in your CV: the years since you graduated. Not in your CV: you are asked (or the AI answers, if you choose that).</li>
      <li>Questions that say “how many” get a number only.</li>
      <li>Notice period in days, or weeks or months when the question asks for them.</li>
      <li>Salary per month in your currency. If a question names another currency (USD, SAR…) it is converted with today's rate, and multiplied by 12 when it asks per year.</li>
      <li>Other questions: your info first, even when a form words the question differently; then you or the AI. Everything you answer joins your info, sorted into categories.</li>
      <li>Exchange rates by <a href="https://www.exchangerate-api.com" target="_blank" rel="noopener">ExchangeRate-API</a>.</li>
    </ul></details>`;
  }

  function setPf(box, k, v) {
    const input = box.querySelector(`input[data-k="${k}"], select[data-k="${k}"]`);
    if (input) { input.value = v == null ? '' : v; return; }
    const group = box.querySelector(`[data-pick="${k}"]`);
    if (group) group.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === v));
  }
  function getPf(box, k) {
    const input = box.querySelector(`input[data-k="${k}"], select[data-k="${k}"]`);
    if (input) return input.value.trim();
    const on = box.querySelector(`[data-pick="${k}"] button.on`);
    return on ? on.dataset.v : '';
  }
  // Fill empty answers from what the AI read in the CV, and mark them.
  function fillSuggestions(box) {
    if (!suggest || !box.querySelector('.pf')) return;
    for (const k of ['phone', 'city', 'graduation_year', 'english']) {
      const v = suggest[k];
      if (v && !getPf(box, k)) {
        setPf(box, k, v);
        const tag = box.querySelector(`.pf[data-f="${k}"] .sug`);
        if (tag) tag.hidden = false;
      }
    }
    paintYears(box);
  }
  function paintDays(box) {
    const v = box.querySelector('[data-k="notice_days"]').value.trim();
    box.querySelectorAll('[data-days] .pick').forEach((b) => b.classList.toggle('on', b.dataset.v === v));
  }
  function paintYears(box) {
    const y = yearsSince(box.querySelector('[data-k="graduation_year"]').value.trim());
    const el = box.querySelector('[data-years]');
    el.innerHTML = y >= 0 ? `So <b>${plural(y, 'year')}</b> of experience for skills in your CV.` : '';
  }
  function paintCurrency(box) {
    const c = box.querySelector('[data-k="currency"]').value || 'EGP';
    box.querySelectorAll('.cur-label').forEach((l) => { l.textContent = c; });
  }
  function collectProfile(box) {
    const out = {};
    let missing = 0;
    for (const f of PF) {
      out[f.k] = getPf(box, f.k);
      const bad = !!f.req && out[f.k] === '';
      box.querySelector(`.pf[data-f="${f.k}"]`).classList.toggle('missing', bad);
      if (bad) missing++;
    }
    out.currency = getPf(box, 'currency') || 'EGP';
    const y = Number(out.graduation_year);
    if (out.graduation_year && (y < 1960 || y > new Date().getFullYear() + 1)) {
      box.querySelector('.pf[data-f="graduation_year"]').classList.add('missing');
      missing++;
    }
    if (missing) {
      const first = box.querySelector('.pf.missing');
      if (first) first.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return null;
    }
    return out;
  }
  async function saveProfile(box) {
    const values = collectProfile(box);
    if (!values) { toast('Fill in the answers marked in red.', true); return false; }
    const r = await api('/api/profile', { profile: values });
    profileData = r;
    toast(r.replaced ? `Saved. ${plural(r.replaced, 'older saved answer')} now follow these.` : 'Answers saved.');
    await refresh();
    return true;
  }

  // ------------------------------------------------------------------ job titles
  function titleEditor(box, input, addBtn, sugBox) {
    let list = [];
    let sugs = [];
    const paint = () => {
      box.innerHTML = list.map((t, i) => `<span class="tchip">${esc(t)}<button type="button" data-i="${i}" aria-label="Remove ${esc(t)}">×</button></span>`).join('');
      box.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { list.splice(+b.dataset.i, 1); paint(); }));
      if (sugBox) {
        const left = sugs.filter((s) => !list.some((t) => t.toLowerCase() === s.toLowerCase()));
        sugBox.innerHTML = left.map((s) => `<button type="button">+ ${esc(s)}</button>`).join('');
        sugBox.querySelectorAll('button').forEach((b, i) => b.addEventListener('click', () => { add(left[i]); }));
      }
    };
    const add = (t) => {
      const v = String(t || '').trim();
      if (v && !list.some((x) => x.toLowerCase() === v.toLowerCase())) list.push(v);
      paint();
    };
    addBtn.addEventListener('click', () => { add(input.value); input.value = ''; input.focus(); });
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); addBtn.click(); } });
    return {
      get: () => { if (input.value.trim()) { add(input.value); input.value = ''; } return list.slice(); },
      set: (l) => { list = l.slice(); paint(); },
      suggest: (l) => { sugs = l || []; paint(); },
    };
  }
  const wTitleEditor = titleEditor($('w-titles'), $('w-title-add'), $('w-title-btn'), $('w-title-sugs'));
  const sTitleEditor = titleEditor($('s-titles'), $('s-title-add'), $('s-title-btn'), null);

  let wJobsFilled = false;
  async function wJobs() {
    if (!wJobsFilled) {
      wJobsFilled = true;
      try {
        const d = await api('/api/settings');
        const saved = String(d.KEYWORDS || '').split(';').map((x) => x.trim()).filter(Boolean);
        wTitleEditor.set(saved.length ? saved : (suggest && suggest.titles) || []);
        $('w-location').value = d.LOCATION || (suggest && suggest.location) || 'Egypt';
      } catch (e) { toast(e.message, true); }
    } else if (!wTitleEditor.get().length && suggest && suggest.titles) {
      wTitleEditor.set(suggest.titles);
    }
    if (!$('w-location').value && suggest && suggest.location) $('w-location').value = suggest.location;
    wTitleEditor.suggest((suggest && suggest.titles) || []);
  }

  // ------------------------------------------------------------------ CV upload (setup and settings)
  function cvStates() {
    if (!state) return;
    const s = state.setup;
    document.querySelectorAll('.drop').forEach((zone) => {
      if (zone.dataset.busy) return;
      zone.classList.toggle('has-cv', !!s.has_cv);
      zone.querySelector('.cv-title').textContent = s.has_cv ? s.cv_name : 'Choose your CV';
      zone.querySelector('.cv-state').textContent = s.has_cv
        ? `${s.cv_chars.toLocaleString()} characters read. Tap to choose another file.` : 'PDF or .txt';
    });
  }
  async function pdfText(file) {
    const pdfjs = await import('./pdfjs/pdf.min.mjs');
    pdfjs.GlobalWorkerOptions.workerSrc = './pdfjs/pdf.worker.min.mjs';
    const doc = await pdfjs.getDocument({ data: new Uint8Array(await file.arrayBuffer()) }).promise;
    const pages = [];
    for (let i = 1; i <= doc.numPages; i++) {
      const page = await doc.getPage(i);
      const content = await page.getTextContent();
      let t = '';
      for (const it of content.items) t += (it.str || '') + (it.hasEOL ? '\n' : ' ');
      pages.push(t.replace(/[ \t]+\n/g, '\n'));
    }
    return pages.join('\n\n');
  }
  function uploadCv(file, zone) {
    if (!file) return;
    if (file.size > 15 * 1024 * 1024) { toast('That file is too large.', true); return; }
    zone.dataset.busy = '1';
    zone.querySelector('.cv-state').textContent = `Reading ${file.name}…`;
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        let text = '';
        if (/\.pdf$/i.test(file.name)) text = await pdfText(file);
        const r = await api('/api/cv', { name: file.name, data: reader.result, text });
        $('cv-preview').textContent = r.preview;
        $('cv-preview-box').hidden = false;
        toast('CV saved.');
        delete zone.dataset.busy;
        await refresh();
        cvStates();
        suggest = null;
        kickSuggest(true);
      } catch (e) {
        delete zone.dataset.busy;
        zone.querySelector('.cv-state').textContent = e.message;
        toast(e.message, true);
      }
    };
    reader.readAsDataURL(file);
  }
  document.querySelectorAll('.drop').forEach((zone) => {
    const input = zone.querySelector('.cv-input');
    input.addEventListener('change', (e) => { uploadCv(e.target.files[0], zone); input.value = ''; });
    ['dragenter', 'dragover'].forEach((ev) => zone.addEventListener(ev, (e) => { e.preventDefault(); zone.classList.add('over'); }));
    ['dragleave', 'drop'].forEach((ev) => zone.addEventListener(ev, (e) => { e.preventDefault(); zone.classList.remove('over'); }));
    zone.addEventListener('drop', (e) => uploadCv(e.dataTransfer.files[0], zone));
  });

  // ------------------------------------------------------------------ background permissions
  const STRICT = /xiaomi|redmi|poco|huawei|honor|oppo|realme|oneplus|vivo|iqoo|tecno|infinix|itel|meizu|asus/;
  function permStates(root) {
    const perm = (state && state.permissions) || {};
    for (const k of ['notifications', 'battery']) {
      const ok = !!perm[k];
      root.querySelectorAll(`[data-perm="${k}"]`).forEach((b) => { b.hidden = ok; });
      root.querySelectorAll(`[data-perm-ok="${k}"]`).forEach((t) => { t.hidden = !ok; });
    }
    const maker = String(perm.maker || '');
    root.querySelectorAll('.maker-row').forEach((row) => {
      row.hidden = !STRICT.test(maker.toLowerCase());
      row.querySelector('.maker-text').textContent = `${maker} phones can stop apps in the background. In App info, allow Autostart and set Battery to “No restrictions”.`;
    });
  }

  // ------------------------------------------------------------------ home
  const STATE_TITLES = {
    idle: 'Ready', starting: 'Starting', signin: 'Sign in to LinkedIn', searching: 'Looking for jobs', working: 'Applying',
    waiting: 'Waiting', error: 'Needs your attention', question: 'Waiting for your answer',
  };
  let countdownAt = 0;
  function renderHome() {
    const s = state;
    if (!s) return;
    const st = s.status || {};
    const running = s.running;
    const test = running && s.options && s.options.no_submit;
    const k = s.stats;
    const pct = Math.min(1, k.today / Math.max(1, k.per_day));
    $('ring-fill').style.strokeDashoffset = String(326.73 * (1 - pct));
    $('k-today').textContent = k.today;
    $('k-today-of').textContent = `of ${k.per_day} a day`;
    const a = s.account || {};
    const tone = running ? (['waiting', 'signin', 'question'].includes(st.state) ? 'wait' : 'run')
      : (s.setup.account && s.setup.has_cv && !a.pro ? 'wait' : st.state === 'error' ? 'err' : '');
    $('state-dot').className = `dot ${tone}`;
    $('state-title').textContent = running ? (test && st.state === 'working' ? 'Test run: filling a form'
      : STATE_TITLES[st.state] || 'Working') : (s.setup.account && s.setup.has_cv && !a.pro ? 'Not active yet'
        : st.state === 'error' ? STATE_TITLES.error : 'Ready');
    const idleMsg = !s.setup.account || !s.setup.has_cv ? 'Finish the setup to start.'
      : !a.pro ? 'Auto apply isn’t active on your account yet. You can start once it is.'
      : 'Press Start. You can lock the phone; it keeps going and sends you a summary.';
    const lastMsg = s.setup.account && s.setup.has_cv && !a.pro ? idleMsg
      : st.message === 'Stopped' ? 'Stopped. Press Start to go on.'
        : st.message && !['Ready', 'Finished'].includes(st.message) ? st.message : idleMsg;
    $('state-msg').textContent = running ? (st.message || 'Working…') : lastMsg;
    // Not active yet is explained by the banner and the line above, not as an error.
    const err = !running && st.state === 'error' && st.error && !(s.setup.account && !a.pro && /active on your account/.test(st.error));
    $('error-msg').hidden = !err;
    if (err) $('error-msg').textContent = st.error;

    const job = running && st.job && (st.job.title || st.job.url) ? st.job : null;
    const cj = $('current-job');
    cj.hidden = !job;
    if (job) {
      cj.href = job.url;
      cj.innerHTML = `<b>${esc(job.title || 'Opening a job…')}</b><span>${esc(job.company || '')}</span>`;
    }
    countdownAt = running && st.next_slot_at && st.next_slot_at > s.now ? st.next_slot_at - s.now + Date.now() / 1000 : 0;
    $('countdown-label').textContent = /Round finished/.test(st.message || '') ? 'Next search in'
      : /daily limit/i.test(st.message || '') ? 'Starts again in' : 'Next application in';
    tick();

    const ready = s.setup.account && s.setup.has_cv;
    const notActive = ready && !a.pro && !running;
    $('btn-start').hidden = running;
    $('btn-start').disabled = !ready;
    $('btn-stop').hidden = !running;
    if (!running) $('btn-stop').disabled = false;
    $('btn-test').hidden = running || !ready;
    $('btn-live').hidden = !running;

    const sess = running ? s.session : (s.session || s.last_session);
    const sessMode = sess ? sess.mode : 'apply';
    $('k-sess').textContent = sess ? (sessMode === 'apply' ? sess.applied.length : sess.filled.length) : 0;
    $('k-sess-label').textContent = sessMode === 'test' ? 'filled (test run)' : 'this session';
    $('k-week').textContent = k.week == null ? 0 : k.week;
    $('k-total').textContent = k.total;
    renderBrief(sess, running);
    const inBrief = new Set(((sess && sess.applied) || []).map((x) => x.id));
    renderRecent((s.recent || []).filter((x) => !inBrief.has(x.id)).slice(0, 5));
    renderBanners();
  }

  function tick() {
    const left = countdownAt - Date.now() / 1000;
    $('countdown').hidden = !(countdownAt && left > 0);
    if (countdownAt && left > 0) $('countdown-val').textContent = fmtDur(left);
  }
  setInterval(tick, 1000);

  function renderBanners() {
    const s = state;
    const items = [];
    const perm = s.permissions || {};
    if (!s.setup.account || !s.setup.has_cv) {
      items.push(['', '<b>Finish the setup</b><span>The app needs your account and your CV before it can apply.</span>',
        '<button class="btn primary" data-act="setup" type="button">Continue the setup</button>']);
    } else if (!(s.account || {}).pro) {
      items.push(['', `<b>Auto apply isn’t active on your account yet</b><span class="who">Signed in as ${esc((s.account || {}).email)}. You can start once it is active.</span>`,
        '<button class="btn" data-act="acc-check" type="button">Check again</button>']);
    } else if (!s.setup.signed_in) {
      items.push(['', '<b>Sign in to LinkedIn</b><span>Easy Apply only works from a signed-in account. You sign in once.</span>',
        '<button class="btn primary" data-act="signin" type="button">Sign in to LinkedIn</button>']);
    }
    const n = s.open_questions || 0;
    if (n) {
      items.push(['', `<b>${n === 1 ? 'A question is' : `${n} questions are`} waiting for your answer</b><span>Answer once and the jobs that waited are tried again by themselves.</span>`,
        '<button class="btn" data-act="answers" type="button">Answer</button>']);
    }
    if (!perm.notifications || !perm.battery) {
      items.push(['info', '<b>Keep it working with the screen off</b><span>Allow notifications and background work.</span>',
        `${perm.notifications ? '' : '<button class="btn" data-perm="notifications" type="button">Notifications</button>'}${
          perm.battery ? '' : '<button class="btn" data-perm="battery" type="button">Background</button>'}`]);
    }
    const u = s.update || {};
    if (!u.disabled && u.last && u.last.available && !['downloading', 'installing', 'confirm'].includes((u.download || {}).state)) {
      items.push(['info', `<b>Version ${esc(u.last.version)} is ready</b><span>Your answers and sign-in stay.</span>`,
        '<button class="btn" data-act="update" type="button">See the update</button>']);
    }
    const sig = JSON.stringify(items);
    const box = $('banners');
    if (box.dataset.sig === sig) return;
    box.dataset.sig = sig;
    box.innerHTML = items.map(([cls, text, btns]) => `<div class="banner ${cls}"><div>${text}</div><div class="row">${btns}</div></div>`).join('');
    box.querySelectorAll('[data-act]').forEach((b) => b.addEventListener('click', () => {
      const a = b.dataset.act;
      if (a === 'setup') startSetup();
      if (a === 'signin') api('/api/linkedin', {}).catch((e) => toast(e.message, true));
      if (a === 'answers') go('info');
      if (a === 'acc-check') accCheck(b);
      if (a === 'update') { go('settings'); setTimeout(() => $('update-card').scrollIntoView({ behavior: 'smooth' }), 300); }
    }));
    box.querySelectorAll('[data-perm]').forEach((b) => b.addEventListener('click',
      () => api('/api/permission', { which: b.dataset.perm }).catch((e) => toast(e.message, true))));
  }

  async function accCheck(btn) {
    if (btn) btn.disabled = true;
    try {
      const r = await api('/api/account/refresh', {});
      toast(r.account.pro ? 'Auto apply is active. Press Start.' : 'Not active yet.');
    } catch (e) { toast(e.message, true); }
    if (btn) btn.disabled = false;
    refresh();
  }

  function jobLink(j) {
    return `<a href="${esc(j.url)}" target="_blank" rel="noopener"><b>${esc(j.title)}</b></a>`
      + `${j.company ? ` <span class="muted">at ${esc(j.company)}</span>` : ''}`;
  }
  function whyText(reason) {
    const r = String(reason || '');
    if (r.includes('Form error:')) {
      return r.split('Form error:')[1].split(' / ').map((p) => {
        const i = p.lastIndexOf(': ');
        return i > 0 ? `<b>${esc(p.slice(0, i).trim())}</b> ${esc(p.slice(i + 2))}` : esc(p.trim());
      }).join('<br>');
    }
    return esc(r.split(' | ').pop());
  }
  function section(cls, title, items, render) {
    if (!items.length) return '';
    return `<div class="brief-sec ${cls}"><h3>${title}<span class="n">${items.length}</span></h3>`
      + `<ul class="brief-list">${items.map(render).join('')}</ul></div>`;
  }
  let lastBriefKey = '';
  function renderBrief(sum, live) {
    const box = $('brief');
    $('brief-title').textContent = live ? 'This session' : 'Last session';
    if (!sum || !sum.total) {
      $('brief-time').textContent = sum && sum.started ? fmtWhen(sum.started) : '';
      let msg = 'When a session ends you get a summary here: what was sent, what got stuck and why.';
      if (live) msg = 'Nothing finished yet. Every application shows up here as it happens.';
      else if (sum) msg = 'The last session found no new jobs. Add more job titles, or choose a longer “Posted within” in Settings.';
      box.innerHTML = `<p class="empty">${msg}</p>`;
      lastBriefKey = '';
      return;
    }
    const end = sum.ended || Date.now() / 1000;
    $('brief-time').textContent = `${fmtWhen(sum.started)} to ${sum.ended ? fmtWhen(sum.ended) : 'now'}, ${fmtDur(end - sum.started)}`;
    const questions = sum.questions || [];
    const key = JSON.stringify([sum.total, sum.applied.length, sum.stuck.length, sum.errors.length, sum.filled.length,
      questions.length, sum.ended]);
    if (key === lastBriefKey) return;
    lastBriefKey = key;
    let html = '';
    html += section('good', 'Sent', sum.applied, (j) => `<li>${jobLink(j)}</li>`);
    html += section('good', sum.mode === 'dry' ? 'Good matches' : 'Filled, not sent (test run)', sum.filled,
      (j) => `<li>${jobLink(j)}${j.score != null ? ` <span class="tag info">${j.score}/10</span>` : ''}</li>`);
    html += section('warn', 'Stuck: form not sent', sum.stuck, (j) => `<li>${jobLink(j)}<span class="why">${whyText(j.reason)}</span></li>`);
    html += section('bad', 'Problems', sum.errors, (j) => `<li>${jobLink(j)}<span class="why">${esc(String(j.reason).split(' | ').pop().slice(0, 200))}</span></li>`);
    html += section('warn', 'Waiting for your answer', questions, (j) => `<li>${jobLink(j)}<span class="why">${
      esc(String(j.reason).split('Waiting for your answer: ').pop())}</span></li>`);
    if (sum.skipped_total) {
      html += `<div class="brief-sec"><h3>Skipped<span class="n">${sum.skipped_total}</span></h3><div class="chipline">`
        + Object.entries(sum.skipped).map(([k, v]) => `<span>${esc(k)}: ${v}</span>`).join('') + '</div></div>';
    }
    if ((sum.stuck.length || sum.errors.length) && !live) {
      html += '<div class="brief-sec"><button class="btn wide" id="btn-retry" type="button">Try the jobs with problems again</button></div>';
    }
    if (!sum.applied.length && !sum.filled.length && !sum.stuck.length && !sum.errors.length && !questions.length) {
      html = `<p class="muted">Nothing sent in this session yet.</p>${html}`;
    }
    box.innerHTML = html;
    const retry = $('btn-retry');
    if (retry) retry.addEventListener('click', () => startBot('apply', { retry: true }));
  }
  let lastRecentSig = '';
  function renderRecent(list) {
    const sig = JSON.stringify(list.map((j) => j.id));
    if (sig === lastRecentSig) return;
    lastRecentSig = sig;
    $('recent-panel').hidden = !list.length;
    $('recent').innerHTML = list.map((j) => `<li><a class="job" href="${esc(j.url)}" target="_blank" rel="noopener">${
      esc(j.title)}</a><span class="co">${esc(j.company || '')}</span><span class="why">${esc(fmtWhen(j.ts))}</span></li>`).join('');
  }

  async function startBot(mode, extra = {}) {
    if (state && state.setup.account && !acc().pro) {
      try { state.account = (await api('/api/account/refresh', {})).account; } catch (e) { /* use what is known */ }
      if (!acc().pro) {
        toast('Auto apply isn’t active on your account yet.', true);
        refresh();
        return;
      }
    }
    primeAlerts();
    if (state && state.permissions && !state.permissions.notifications) {
      api('/api/permission', { which: 'notifications' }).catch(() => {});
    }
    try {
      await api('/api/start', { mode, once: false, retry: !!extra.retry });
      toast(mode === 'apply' ? 'Started. You can lock the phone.' : 'Test run started. Forms are filled but not sent.');
    } catch (e) {
      toast(e.message, true);
    }
    refresh();
  }
  $('btn-start').addEventListener('click', async () => {
    $('btn-start').disabled = true;
    await startBot('apply');
    setTimeout(() => { $('btn-start').disabled = false; }, 800);
  });
  $('btn-test').addEventListener('click', () => startBot('test'));
  $('btn-stop').addEventListener('click', async () => {
    $('btn-stop').disabled = true;
    try { await api('/api/stop', {}); toast('Stopping after the current step…'); } catch (e) { toast(e.message, true); }
    refresh();
  });
  $('btn-live').addEventListener('click', () => { if (window.AA && window.AA.liveView) window.AA.liveView(); });

  // ------------------------------------------------------------------ activity log
  let logSeq = 0;
  const LOG_MAX = 900;
  function classify(text) {
    if (/^\s*APPLIED\b|^\s*\+ /.test(text)) return 'ok';
    if (/^SESSION BRIEF|^=+$/.test(text)) return 'head';
    if (/Question for you:/.test(text)) return 'q';
    if (/Your answer:|Saved your answer|From your setup:/.test(text)) return 'info';
    if (/\bError:|Unexpected error|refused|blocks openrouter/i.test(text)) return 'bad';
    if (/Stuck:|LinkedIn rejected|Fixing '|Tip:|Daily limit|Please sign in|Tapped|^\s*! /.test(text)) return 'warn';
    if (/Match \d+\/10|Searching "|Form filled|Next application slot/.test(text)) return 'info';
    if (/Skipped:|AI answer:/.test(text)) return 'dim';
    return '';
  }
  function appendLog(lines) {
    if (!lines.length) return;
    const box = $('log');
    const frag = document.createDocumentFragment();
    for (const l of lines) {
      if (/^=+$/.test(l.text)) {
        const sep = document.createElement('div');
        sep.className = 'sep';
        frag.appendChild(sep);
        continue;
      }
      const div = document.createElement('div');
      const t = document.createElement('span');
      t.className = 't';
      t.textContent = l.t;
      const m = document.createElement('span');
      m.className = classify(l.text);
      m.textContent = l.text;
      div.append(t, m);
      frag.appendChild(div);
    }
    box.appendChild(frag);
    while (box.childElementCount > LOG_MAX) box.firstElementChild.remove();
    if (view === 'log' && $('autoscroll').checked) window.scrollTo(0, document.body.scrollHeight);
  }
  async function logLoop() {
    try {
      const d = await api(`/api/log?since=${logSeq}`);
      appendLog(d.lines);
      logSeq = d.next;
    } catch (e) { /* app closed */ }
    setTimeout(logLoop, document.hidden ? 20000 : 1000);
  }

  // ------------------------------------------------------------------ questions for you
  const KIND_HELP = {
    numeric: 'LinkedIn wants a number here.', choice: 'Pick one.', multi: 'Tick all that apply.',
    textarea: 'A few sentences, usually written for this job, so it is not kept unless you tick the box.', text: '',
  };
  const shown = (v) => (Array.isArray(v) ? v.join(', ') : String(v == null ? '' : v));

  function makeAnswer(box, item, submit) {
    const opts = item.options || [];
    const multi = item.kind === 'multi';
    const w = { focus() {} };
    if (opts.length && (multi || opts.length <= 8)) {
      const picked = new Set();
      box.innerHTML = `<div class="q-options" role="${multi ? 'group' : 'radiogroup'}">${opts.map((o, i) => `<button type="button"
        class="q-opt${multi ? ' multi' : ''}" role="${multi ? 'checkbox' : 'radio'}" aria-checked="false" data-i="${i}">
        <span class="box"></span><span>${esc(o)}</span></button>`).join('')}</div>`;
      const btns = [...box.querySelectorAll('.q-opt')];
      const paint = () => btns.forEach((b) => {
        const on = picked.has(opts[+b.dataset.i]);
        b.classList.toggle('on', on);
        b.setAttribute('aria-checked', on ? 'true' : 'false');
      });
      const toggle = (o) => {
        if (multi) { if (picked.has(o)) picked.delete(o); else picked.add(o); } else { picked.clear(); picked.add(o); }
        paint();
      };
      btns.forEach((b) => b.addEventListener('click', () => toggle(opts[+b.dataset.i])));
      w.set = (v) => (Array.isArray(v) ? v : [v]).forEach((x) => { if (opts.includes(x)) toggle(x); });
      w.value = () => (multi ? [...picked] : ([...picked][0] || ''));
      w.markAI = (vals) => btns.forEach((b) => {
        const has = (vals || []).includes(opts[+b.dataset.i]);
        const tag = b.querySelector('.ai');
        if (has && !tag) b.insertAdjacentHTML('beforeend', '<span class="ai">Suggested</span>');
        if (!has && tag) tag.remove();
      });
    } else if (opts.length) {
      box.innerHTML = `<select aria-label="Answer"><option value="">Choose…</option>${
        opts.map((o) => `<option value="${esc(o)}">${esc(o)}</option>`).join('')}</select>`;
      const sel = box.querySelector('select');
      w.set = (v) => { sel.value = Array.isArray(v) ? v[0] : v; };
      w.value = () => sel.value;
      w.markAI = (vals) => [...sel.options].forEach((o) => {
        if (o.value) o.textContent = (vals || []).includes(o.value) ? `${o.value} (suggested)` : o.value;
      });
    } else {
      const long = item.kind === 'textarea';
      const num = item.kind === 'numeric';
      const max = item.maxlen ? ` maxlength="${item.maxlen}"` : '';
      const ph = esc(item.placeholder || (num ? 'A number, like 2' : 'Type your answer'));
      box.innerHTML = long ? `<textarea rows="5"${max} placeholder="${ph}" aria-label="Answer"></textarea>`
        : `<input type="text"${num ? ' inputmode="decimal"' : ''}${max} placeholder="${ph}" aria-label="Answer" autocomplete="off">`;
      if (item.maxlen) box.insertAdjacentHTML('beforeend', '<span class="counter"></span>');
      const el = box.querySelector(long ? 'textarea' : 'input');
      const counter = box.querySelector('.counter');
      const count = () => { if (counter) counter.textContent = `${el.value.length}/${item.maxlen}`; };
      el.addEventListener('input', count);
      el.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !long) { e.preventDefault(); submit(); } });
      count();
      w.set = (v) => { el.value = shown(v); count(); };
      w.value = () => el.value.trim();
      w.markAI = () => {};
      w.focus = () => el.focus();
    }
    return w;
  }
  function renderChips(box, item, w) {
    const sug = item.suggestions || [];
    let html = sug.map((v, i) => {
      const t = shown(v);
      const long = t.length > 70;
      return `<button type="button" class="q-chip${long ? ' long' : ''}" data-i="${i}">${esc(long && t.length > 220 ? `${t.slice(0, 220)}…` : t)}</button>`;
    }).join('');
    if (item.loading) html += '<span class="loading">Finding answers in your CV…</span>';
    else if (!sug.length) html = '<span class="muted small">No suggestions this time. Type your answer.</span>';
    box.innerHTML = html;
    box.querySelectorAll('.q-chip').forEach((b) => b.addEventListener('click', () => w.set(sug[+b.dataset.i])));
    w.markAI(item.kind === 'multi' ? sug : sug.slice(0, 1));
  }

  let qItem = null;
  let qWidget = null;
  let qSig = '';
  let qTimerAt = 0;
  const qDone = new Set();
  function hideQuestion() {
    $('qcard').hidden = true;
    qItem = null;
    qWidget = null;
    qSig = '';
    renderQuestionBar();
  }
  function qError(msg) {
    $('q-err').textContent = msg;
    $('q-err').hidden = !msg;
  }
  function renderQuestionBar() {
    $('q-global').hidden = !(qItem && view !== 'home' && view !== 'setup');
    if (qItem) $('q-global-text').textContent = qItem.question;
  }
  $('q-global').addEventListener('click', () => go('home'));
  function renderQuestion(q) {
    if (!q || qDone.has(q.id)) {
      if (qItem) hideQuestion();
      return;
    }
    if (!qItem || qItem.id !== q.id) {
      qItem = q;
      $('qcard').hidden = false;
      const job = q.job || {};
      $('q-job').hidden = !job.title;
      $('q-job').href = job.url || '#';
      $('q-job').innerHTML = `For <b>${esc(job.title)}</b>${job.company ? ` at ${esc(job.company)}` : ''}`;
      $('q-text').textContent = q.question;
      $('q-kind').textContent = [KIND_HELP[q.kind] || '', q.maxlen ? `Up to ${q.maxlen} characters.` : ''].join(' ').trim();
      const rej = (q.rejected || []).map((r) => `“${r}”`).join(', ');
      $('q-rejected').hidden = !q.hint;
      $('q-rejected').textContent = q.hint ? `LinkedIn did not accept ${rej || 'the answer'}: ${q.hint}` : '';
      qWidget = makeAnswer($('q-input'), q, sendAnswer);
      $('q-remember').checked = q.remember !== false;
      const sim = q.similar || [];
      $('q-similar').hidden = !sim.length;
      $('q-similar').innerHTML = sim.length ? `<span class="q-label">You answered similar questions</span>${sim.map((x, i) =>
        `<button type="button" class="q-sim" data-i="${i}"><span>${esc(x.question)}</span><b>${esc(shown(x.answer))}</b></button>`).join('')}` : '';
      $('q-similar').querySelectorAll('.q-sim').forEach((b) => b.addEventListener('click', () => qWidget.set(sim[+b.dataset.i].answer)));
      qError('');
      $('q-send').disabled = false;
      $('q-skip').disabled = false;
      qSig = '';
      beep();
      if (view === 'home') window.scrollTo({ top: 0, behavior: 'smooth' });
    }
    const sig = JSON.stringify([q.loading, q.suggestions, q.reason]);
    if (sig !== qSig) {
      qSig = sig;
      renderChips($('q-chips'), q, qWidget);
      $('q-reason').textContent = q.reason || '';
    }
    qTimerAt = q.timeout_at ? q.timeout_at - state.now + Date.now() / 1000 : 0;
    renderQuestionBar();
    qTick();
  }
  function qTick() {
    if (!qItem) return;
    const el = $('q-timer');
    if (!qTimerAt) { el.textContent = 'The form waits for you'; el.className = 'q-timer'; return; }
    const left = qTimerAt - Date.now() / 1000;
    el.textContent = left > 0 ? `Skips this job for now in ${fmtDur(left)}` : 'Skipping this job for now…';
    el.className = `q-timer${left < 60 ? ' urgent' : ''}`;
  }
  setInterval(qTick, 1000);
  async function sendAnswer() {
    if (!qItem || !qWidget) return;
    const answer = qWidget.value();
    if (!answer || (Array.isArray(answer) && !answer.length)) {
      qError((qItem.options || []).length ? 'Pick an answer first.' : 'Type an answer or tap a suggestion first.');
      return;
    }
    $('q-send').disabled = true;
    try {
      await api('/api/answer', { id: qItem.id, answer, remember: $('q-remember').checked });
      qDone.add(qItem.id);
      hideQuestion();
      toast('Answer sent. The form continues.');
    } catch (e) {
      qError(e.message);
      $('q-send').disabled = false;
    }
    refresh();
  }
  $('q-send').addEventListener('click', sendAnswer);
  $('q-skip').addEventListener('click', async () => {
    if (!qItem) return;
    $('q-skip').disabled = true;
    try {
      await api('/api/skip-question', { id: qItem.id });
      qDone.add(qItem.id);
      hideQuestion();
      toast('Skipped this job.');
    } catch (e) {
      qError(e.message);
      $('q-skip').disabled = false;
    }
    refresh();
  });

  let audioCtx = null;
  function primeAlerts() {
    try {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();
    } catch (e) { /* no audio */ }
  }
  document.addEventListener('pointerdown', () => primeAlerts(), { once: true });
  function beep() {
    if (!audioCtx || audioCtx.state !== 'running') return;
    const t = audioCtx.currentTime;
    [[660, 0], [880, 0.17]].forEach(([freq, delay]) => {
      const o = audioCtx.createOscillator();
      const g = audioCtx.createGain();
      o.type = 'sine';
      o.frequency.value = freq;
      g.gain.setValueAtTime(0.0001, t + delay);
      g.gain.exponentialRampToValueAtTime(0.2, t + delay + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, t + delay + 0.15);
      o.connect(g).connect(audioCtx.destination);
      o.start(t + delay);
      o.stop(t + delay + 0.17);
    });
  }

  let lastHere = 0;
  function here() {
    if (Date.now() - lastHere < 30000) return;
    lastHere = Date.now();
    api('/api/here', {}).catch(() => {});
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { here(); refresh(); } });
  document.addEventListener('pointerdown', here);

  // ------------------------------------------------------------------ settings
  const LIST_KEYS = ['SKIP_COMPANIES', 'SKIP_TITLE_WORDS'];
  const PLAIN_KEYS = ['LOCATION', 'DATE_POSTED', 'PER_HOUR', 'PER_DAY', 'MIN_SCORE', 'ASK_TIMEOUT_MIN', 'UPDATE_SOURCE', 'ABOUT_ME'];
  async function loadSettings() {
    try {
      const d = await api('/api/settings');
      for (const k of PLAIN_KEYS) { const el = $(`s-${k}`); if (el) el.value = d[k] == null ? '' : d[k]; }
      for (const k of LIST_KEYS) $(`s-${k}`).value = String(d[k] || '').split(';').map((x) => x.trim()).filter(Boolean).join('\n');
      sTitleEditor.set(String(d.KEYWORDS || '').split(';').map((x) => x.trim()).filter(Boolean));
      document.querySelectorAll('#v-settings .chips').forEach((box) => {
        const vals = String(d[box.dataset.key] || '').toLowerCase().split(/[,;\s]+/);
        box.querySelectorAll('input').forEach((i) => { i.checked = vals.includes(i.value); });
      });
      const newq = String(d.NEW_QUESTIONS || '').toLowerCase() === 'ai' ? 'ai' : 'ask';
      document.querySelectorAll('input[name=newq]').forEach((r) => { r.checked = r.value === newq; });
      if (!$('s-DATE_POSTED').value) $('s-DATE_POSTED').value = 'week';
      $('min-score-val').textContent = `${$('s-MIN_SCORE').value}/10`;
      settingsLive();
    } catch (e) { toast(e.message, true); }
  }
  function settingsLive() {
    if (!state) return;
    const s = state.setup;
    $('settings-running').hidden = !state.running;
    renderAccountCard();
    $('li-state').textContent = s.signed_in ? 'Signed in' : 'Not signed in';
    cvStates();
  }
  $('s-MIN_SCORE').addEventListener('input', () => { $('min-score-val').textContent = `${$('s-MIN_SCORE').value}/10`; });
  document.querySelectorAll('.save-settings').forEach((btn) => btn.addEventListener('click', async () => {
    const body = {};
    for (const k of PLAIN_KEYS) { const el = $(`s-${k}`); if (el) body[k] = el.value; }
    for (const k of LIST_KEYS) body[k] = $(`s-${k}`).value;
    body.KEYWORDS = sTitleEditor.get().join('\n');
    document.querySelectorAll('#v-settings .chips').forEach((box) => {
      body[box.dataset.key] = [...box.querySelectorAll('input:checked')].map((i) => i.value).join(',');
    });
    const newq = document.querySelector('input[name=newq]:checked');
    if (newq) body.NEW_QUESTIONS = newq.value;
    try {
      const r = await api('/api/settings', body);
      toast(r.running ? 'Saved. The question setting applies now, the rest from the next start.' : 'Saved.');
      refresh();
    } catch (e) { toast(e.message, true); }
  }));
  $('btn-save-answers').addEventListener('click', async () => {
    try { if (await saveProfile($('s-answers'))) { paintMainCount(); loadFacts(); } } catch (e) { toast(e.message, true); }
  });
  function renderAccountCard() {
    const a = acc();
    $('acc-state').textContent = !a.signed_in ? 'Signed out' : a.pro ? 'Active' : 'Not active yet';
    $('acc-state').className = `tag ${!a.signed_in ? '' : a.pro ? 'good' : 'warn'}`;
    $('acc-who').innerHTML = a.signed_in ? `${esc(a.name || a.email)}${a.name ? `<small>${esc(a.email)}</small>` : ''}` : 'Not signed in';
    $('acc-pro').textContent = !a.signed_in ? 'Sign in to use the AI and start auto apply.'
      : a.pro ? `Auto apply is active.${a.max_per_day ? ` At most ${a.max_per_day} applications a day.` : ''}`
        : 'Auto apply isn’t active on your account yet. You can start once it is.';
    $('btn-acc-check').hidden = !a.signed_in;
    $('btn-acc-out').hidden = !a.signed_in;
    $('btn-acc-signin').hidden = !!a.signed_in;
    $('acc-del-box').hidden = !a.signed_in;
    $('acc-server-box').hidden = !!a.server_built_in;
    if (!a.server_built_in && document.activeElement !== $('s-server')) $('s-server').value = (a.server || '').replace(/^https:\/\//, '');
    const priv = a.server ? `${a.server}/privacy` : '';
    $('privacy-link').hidden = !priv;
    if (priv) $('privacy-link').href = priv;
  }
  $('btn-acc-check').addEventListener('click', async () => {
    $('acc-out').textContent = 'Checking…';
    try {
      const r = await api('/api/test-ai', {});
      $('acc-out').textContent = r.message;
    } catch (e) { $('acc-out').textContent = e.message; }
    refresh();
  });
  $('btn-acc-signin').addEventListener('click', () => startSetup('account'));
  $('btn-acc-out').addEventListener('click', async () => {
    try { await api('/api/account/logout', {}); toast('Signed out.'); } catch (e) { toast(e.message, true); }
    $('acc-out').textContent = '';
    refresh();
  });
  $('btn-server').addEventListener('click', async () => {
    try {
      await api('/api/account/server', { server: $('s-server').value.trim() });
      accInfoAsked = false;
      toast('Saved. Sign in on this server.');
    } catch (e) { toast(e.message, true); }
    refresh();
  });
  $('btn-acc-del').addEventListener('click', async () => {
    const pw = $('acc-del-pw').value;
    if (!pw) { toast('Type your password first.', true); return; }
    $('btn-acc-del').disabled = true;
    try {
      await api('/api/account/delete', { password: pw });
      $('acc-del-pw').value = '';
      $('acc-del-box').open = false;
      toast('Your account was deleted.');
    } catch (e) { toast(e.message, true); }
    $('btn-acc-del').disabled = false;
    refresh();
  });
  $('btn-rerun').addEventListener('click', async () => {
    wJobsFilled = false;
    await api('/api/setup', { done: false }).catch(() => {});
    await refresh();
    startSetup('welcome');
  });

  // ------------------------------------------------------------------ app updates
  const UPDATE_BUSY = ['downloading', 'installing', 'confirm'];
  function renderUpdate(u, running) {
    if (!u) return;
    const cur = (u.current && u.current.name) || '';
    $('help-version').textContent = cur ? `Version ${cur}${state && state.build && state.build.flavor === 'play' ? '' : ' (GitHub)'}.` : '';
    $('update-card').hidden = !!u.disabled;
    if (u.disabled) return;
    $('app-version').textContent = cur ? `Version ${cur}` : '';
    const last = u.last || null;
    const dl = u.download || {};
    const st = dl.state || 'idle';
    let status = '';
    if (u.checking) status = 'Checking for updates…';
    else if (st !== 'idle' && dl.message) status = dl.message;
    else if (last) status = last.message || '';
    $('update-status').textContent = status;
    $('update-status').className = `hint${st === 'error' || (last && last.ok === false && st === 'idle') ? ' bad-text' : ''}`;
    const avail = !!(last && last.available);
    $('update-box').hidden = !avail;
    if (avail) {
      $('update-version').textContent = `Version ${last.version}`;
      $('update-notes').textContent = last.notes || '';
      $('update-notes').hidden = !last.notes;
    }
    $('update-meter-wrap').hidden = st !== 'downloading';
    $('update-meter').style.width = `${dl.progress || 0}%`;
    const busyNow = UPDATE_BUSY.includes(st);
    const btn = $('btn-update-install');
    btn.hidden = !avail;
    btn.disabled = busyNow || running;
    btn.textContent = running ? 'Stop the app to update' : busyNow ? 'Updating…' : 'Download and install';
    $('btn-update-check').disabled = !!u.checking || busyNow;
  }
  $('btn-update-check').addEventListener('click', async () => {
    try { await api('/api/update/check', {}); } catch (e) { toast(e.message, true); }
    refresh();
  });
  $('btn-update-install').addEventListener('click', async () => {
    try { await api('/api/update/install', {}); toast('Downloading the update…'); } catch (e) { toast(e.message, true); }
    refresh();
  });

  // ------------------------------------------------------------------ saved answers
  let openItems = [];
  let lastOpenCount = -1;
  async function loadOpen() {
    try {
      openItems = (await api('/api/open-questions')).items || [];
      renderOpen();
    } catch (e) { toast(e.message, true); }
  }
  function renderOpen() {
    $('open-card').hidden = !openItems.length;
    $('open-count').textContent = openItems.length;
    const list = $('open-list');
    list.innerHTML = '';
    for (const it of openItems) {
      const div = document.createElement('div');
      div.className = 'oq';
      const jobs = it.jobs || [];
      const jobLinks = jobs.slice(0, 3).map((j) => `<a href="${esc(j.url)}" target="_blank" rel="noopener">${
        esc(j.title || 'a job')}${j.company ? ` at ${esc(j.company)}` : ''}</a>`).join(', ');
      const meta = [jobs.length ? `${plural(jobs.length, 'job')} waited for this: ${jobLinks}${jobs.length > 3 ? ` and ${jobs.length - 3} more` : ''}` : '',
        it.hint ? `LinkedIn said: ${esc(it.hint)}` : ''].filter(Boolean).join('. ');
      div.innerHTML = `<div class="oq-q">${esc(it.question)}</div><div class="oq-meta">${meta}</div>
        <div class="oq-input"></div><div class="q-chips"></div>
        <div class="row end"><button class="btn ghost-danger" type="button" data-dismiss>Dismiss</button>
        <button class="btn primary" type="button" data-save>Save answer</button></div>`;
      list.appendChild(div);
      let w = null;
      const submit = () => saveOpen(it, w);
      w = makeAnswer(div.querySelector('.oq-input'), it, submit);
      renderChips(div.querySelector('.q-chips'), { ...it, loading: false }, w);
      div.querySelector('[data-save]').addEventListener('click', submit);
      div.querySelector('[data-dismiss]').addEventListener('click', () => dismissOpen(it));
    }
  }
  async function saveOpen(it, w) {
    const answer = w.value();
    if (!answer || (Array.isArray(answer) && !answer.length)) { toast('Type an answer or tap a suggestion first.', true); return; }
    try {
      const r = await api('/api/open-questions', { key: it.key, answer });
      toast(r.retried ? `Saved. ${plural(r.retried, 'job')} will be tried again.` : 'Answer saved.');
      openItems = openItems.filter((x) => x.key !== it.key);
      renderOpen();
      loadFacts();
    } catch (e) { toast(e.message, true); }
    refresh();
  }
  async function dismissOpen(it) {
    try {
      await api('/api/open-questions', { key: it.key, dismiss: true });
      openItems = openItems.filter((x) => x.key !== it.key);
      renderOpen();
    } catch (e) { toast(e.message, true); }
    refresh();
  }

  // ------------------------------------------------------------------ your info
  const CAT_HINTS = {
    Personal: 'Nationality, birth date, marital status…',
    Contact: 'Email, address, other numbers…',
    Education: 'Degree, university, major, GPA…',
    Experience: 'Years with a skill or tool. When a form asks how long you worked with it, this comes before your CV.',
    Skills: 'Things you know or use, like “Do you know Linux? Yes”.',
    Certifications: 'CCNA, AWS… and when you got them.',
    Languages: 'Languages you speak and how well.',
    'Salary & availability': 'Pay and start dates your main answers do not cover.',
    'Work preferences': 'Remote work, shifts, travel, overtime…',
    'Work permit': 'Visas, permits, citizenship.',
    Links: 'LinkedIn, GitHub, portfolio.',
    Other: 'Anything else forms ask.',
  };
  let facts = [];
  let categories = [];
  const openCats = new Set();  // categories you opened stay open
  async function loadInfo() {
    loadOpen();
    await loadProfile();
    renderProfileForm($('s-answers'), false);
    paintMainCount();
    await loadFacts();
  }
  function paintMainCount() {
    const saved = (profileData && profileData.profile) || {};
    $('main-n').textContent = PF.filter((f) => saved[f.k]).length || '';
  }
  async function loadFacts() {
    try {
      const d = await api('/api/facts');
      facts = d.facts || [];
      categories = d.categories || [];
      renderCats();
    } catch (e) { toast(e.message, true); }
  }
  const normQ = (q) => String(q).trim().toLowerCase().replace(/\s+/g, ' ');
  const valueText = (v) => (Array.isArray(v) ? v.join(' | ') : String(v == null ? '' : v));
  function factRow(f) {
    const asked = (f.asked || []).filter((a) => a !== normQ(f.label));
    const exp = f.category === 'Experience' && /^\d+(\.\d+)?$/.test(valueText(f.value).trim());
    return `<div class="fact" data-id="${esc(f.id)}" data-list="${Array.isArray(f.value) ? 1 : ''}">
      <div class="fact-label">${esc(f.label)}${f.source === 'ai' ? ' <span class="tag info">AI guess</span>' : ''}</div>
      <div class="row"><input type="text" class="fact-value" value="${esc(valueText(f.value))}" aria-label="Answer to ${esc(f.label)}">${
        exp ? '<span class="unit">years</span>' : ''}<button class="icon-btn" data-del type="button" aria-label="Delete ${esc(f.label)}">×</button></div>
      <details class="fact-more"><summary>More</summary>
        ${asked.length ? `<p class="hint">Also answered:</p><ul class="asked">${asked.map((a) => `<li>${esc(a)}</li>`).join('')}</ul>` : ''}
        <label class="move">Category <select data-move>${categories.map((c) => `<option${c === f.category ? ' selected' : ''}>${esc(c)}</option>`).join('')}</select></label>
      </details></div>`;
  }
  function renderCats() {
    const q = $('info-q').value.trim().toLowerCase();
    const box = $('cats');
    const hit = (f) => !q || `${f.label} ${valueText(f.value)} ${(f.asked || []).join(' ')} ${f.category}`.toLowerCase().includes(q);
    let shown = 0;
    box.innerHTML = categories.map((c) => {
      const list = facts.filter((f) => f.category === c && hit(f));
      if (q && !list.length) return '';
      shown += list.length;
      const exp = c === 'Experience';
      return `<details class="panel cat" data-cat="${esc(c)}"${openCats.has(c) || (q && list.length) ? ' open' : ''}>
        <summary><span>${esc(c)}</span><b class="cat-n">${list.length || ''}</b></summary>
        <div class="cat-body"><p class="hint">${esc(CAT_HINTS[c] || '')}</p>
        ${list.map(factRow).join('')}
        <div class="add-fact">
          <input type="text" class="af-label" placeholder="${exp ? 'Skill or tool, like Python' : 'Question, like Do you have a car?'}" aria-label="New ${esc(c)} question">
          <div class="row"><input type="text" class="af-value" placeholder="${exp ? 'Years, like 3' : 'Answer'}"${exp ? ' inputmode="decimal"' : ''} aria-label="Answer">
          <button class="btn" data-add type="button">Add</button></div>
        </div></div></details>`;
    }).join('');
    $('info-empty').hidden = !q || shown > 0;
    box.querySelectorAll('.fact').forEach((row) => {
      const id = row.dataset.id;
      const input = row.querySelector('.fact-value');
      input.addEventListener('change', () => {
        const v = input.value.trim();
        if (!v) { toast('An answer cannot be empty. Use × to delete it.', true); return; }
        changeFact({ op: 'update', id, value: row.dataset.list ? v.split(/\s*\|\s*/).filter(Boolean) : v }, 'Saved.');
      });
      row.querySelector('[data-del]').addEventListener('click', () => changeFact({ op: 'delete', id }, 'Deleted.'));
      row.querySelector('[data-move]').addEventListener('change', (e) => {
        openCats.add(e.target.value);  // show where it went
        changeFact({ op: 'update', id, category: e.target.value }, `Moved to ${e.target.value}.`);
      });
    });
    box.querySelectorAll('details.cat').forEach((cat) => {
      cat.addEventListener('toggle', () => {
        if (q) return;  // opened by the search
        if (cat.open) openCats.add(cat.dataset.cat); else openCats.delete(cat.dataset.cat);
      });
      const add = () => {
        const label = cat.querySelector('.af-label').value.trim();
        const value = cat.querySelector('.af-value').value.trim();
        if (!label || !value) { toast(cat.dataset.cat === 'Experience' ? 'Type the skill and the years.' : 'Type the question and the answer.', true); return; }
        changeFact({ op: 'add', category: cat.dataset.cat, label, value }, 'Added.');
      };
      cat.querySelector('[data-add]').addEventListener('click', add);
      cat.querySelector('.af-value').addEventListener('keydown', (e) => { if (e.key === 'Enter') add(); });
    });
  }
  async function changeFact(body, done) {
    try {
      const d = await api('/api/facts', body);
      facts = d.facts || facts;
      categories = d.categories || categories;
      renderCats();
      toast(done);
      refresh();
    } catch (e) { toast(e.message, true); }
  }
  $('info-q').addEventListener('input', renderCats);

  // ------------------------------------------------------------------ jobs
  let jobs = [];
  async function loadJobs() {
    try {
      jobs = (await api('/api/jobs')).jobs;
      renderJobs();
    } catch (e) { toast(e.message, true); }
  }
  function renderJobs() {
    const f = $('jobs-filter').value;
    const q = $('jobs-q').value.trim().toLowerCase();
    store('aa-jobs-filter', f);
    const rows = jobs.filter((j) => (f === 'all' || j.group === f) && (!q || `${j.title} ${j.company}`.toLowerCase().includes(q)));
    $('jobs-count').textContent = plural(rows.length, 'job');
    const failed = jobs.filter((j) => ['error', 'stuck'].includes(j.status)).length;
    $('jobs-retry').hidden = !failed || !state || state.running;
    $('jobs-retry-title').textContent = `${plural(failed, 'job')} had problems`;
    $('jobs-empty').hidden = rows.length > 0;
    $('jobs-rows').innerHTML = rows.slice(0, 600).map((j) => {
      const qa = (j.qa || []).length ? `<details><summary>${plural(j.qa.length, 'answer')} given</summary><ul>${
        j.qa.map((x) => `<li><span class="muted">${esc(x.q)}:</span> ${esc(x.a)}</li>`).join('')}</ul></details>` : '';
      const reason = j.status === 'stuck' ? whyText(j.reason) : esc(String(j.reason || '').split(' | ').pop());
      return `<li><a class="job" href="${esc(j.url)}" target="_blank" rel="noopener">${esc(j.title || `Job ${j.id}`)}</a>
        <span class="co">${esc(j.company || '')}</span>
        <div class="meta"><span class="tag ${esc(j.tone)}">${esc(j.label)}</span>${j.score == null ? '' : `<span>fit ${j.score}/10</span>`}<span>${esc(fmtWhen(j.ts))}</span></div>
        ${reason ? `<span class="why">${reason}</span>` : ''}${qa}</li>`;
    }).join('');
  }
  $('btn-jobs-retry').addEventListener('click', async () => {
    await startBot('apply', { retry: true });
    go('home');
  });
  $('jobs-filter').value = store('aa-jobs-filter') || 'applied';
  $('jobs-filter').addEventListener('change', renderJobs);
  $('jobs-q').addEventListener('input', renderJobs);
  setInterval(() => { if (view === 'jobs' && !document.hidden) loadJobs(); }, 15000);

  // ------------------------------------------------------------------ start
  window.aaRefresh = () => { refresh(); if (view === 'settings') loadSettings(); };
  window.aaShow = (v) => go(v === 'dashboard' ? 'home' : v);
  setTimeout(() => api('/api/update/check', { auto: true }).catch(() => {}), 1500);
  loop();
  logLoop();
})();
