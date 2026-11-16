/* JSTB extension: applies to the job open in this tab. The background page opened the tab and says which job it is. */
(async function () {
  'use strict';
  if (window.__jstbDriver) return;
  window.__jstbDriver = true;
  const F = window.JSTBForm;
  const { sleep, vis, clean, send } = F;

  const hello = await send({ t: 'hello', url: location.href });
  if (!hello || !hello.job) return;
  const job = hello.job;
  const noSubmit = !!hello.noSubmit;
  const qa = [];
  const log = (text) => send({ t: 'log', text });
  /** What the page looked like when it got stuck (without scripts and styles), so the form can be studied later. */
  function snapshot() {
    try {
      const root = [...document.querySelectorAll('.jobs-easy-apply-modal, [data-test-modal-id="easy-apply-modal"], [role="dialog"], dialog[open]')].find(vis)
        || [...document.querySelectorAll('form')].find(vis) || document.querySelector('main') || document.body;
      const copy = root.cloneNode(true);
      copy.querySelectorAll('script, style, svg, img, video, noscript, link').forEach((x) => x.remove());
      copy.querySelectorAll('input:not([type="radio"]):not([type="checkbox"]), textarea').forEach((x) => x.setAttribute('value', ''));
      return copy.outerHTML.replace(/\s+/g, ' ').slice(0, 80000);
    } catch (e) { return ''; }
  }
  const done = (status, reason, extra) => send(Object.assign({ t: 'result', status, reason: reason || '', qa,
    html: status === 'stuck' ? (lastSnap || snapshot()) : '', pageUrl: location.href }, extra || {}));
  let lastSnap = '';       // the form as it was just before it was closed
  const pageText = () => String(document.body ? document.body.innerText : '');
  const CAPTCHA = 'iframe[src*="recaptcha"], iframe[src*="hcaptcha"], iframe[src*="turnstile"], .g-recaptcha, .h-captcha, #captcha';
  const hasCaptcha = () => [...document.querySelectorAll(CAPTCHA)].some((c) => vis(c) && c.getBoundingClientRect().height > 40);

  // ------------------------------------------------------------------ LinkedIn Easy Apply
  const LI = {
    apply: ".jobs-apply-button--top-card button, .jobs-s-apply button, button.jobs-apply-button, "
      + "button[aria-label*='Easy Apply'], a[aria-label*='Easy Apply'], a[href*='openSDUIApplyFlow']",
    modal: ".jobs-easy-apply-modal, [data-test-modal-id='easy-apply-modal'], [role='dialog'], dialog[open], .artdeco-modal",
    top: ['.job-details-jobs-unified-top-card__container--two-pane', '.jobs-unified-top-card', '.jobs-details__main-content', 'main'],
    desc: ['#job-details', '.jobs-description__content', '.jobs-description-content__text', '.jobs-box__html-content',
      "[class*='jobs-description']", "[data-testid*='description']"],
    applied: /\bApplied\s+\d+\s+\w+\s+ago\b|\bApplied now\b|Application submitted|See application/i,
    closed: /No longer accepting applications/i,
    limit: /limit daily submissions|reached (?:the|your) (?:daily )?(?:easy apply )?(?:application )?limit|come back tomorrow/i,
    sent: /application (?:was )?sent|application submitted/i,
    next: /^\s*(review( your application)?|next|continue|continue to next step)\s*$/i,
    submit: /^\s*submit( application)?\s*$/i,
    dialogs: '[role="dialog"], dialog[open], .artdeco-modal, .artdeco-toast-item, .artdeco-inline-feedback',
  };
  const first = (list) => { for (const s of list) { const e = document.querySelector(s); if (e) return e; } return null; };
  const liLabel = (b) => (b.getAttribute('aria-label') || '') + ' ' + (b.textContent || '');
  const dialogText = () => [...document.querySelectorAll(LI.dialogs)].filter(vis).map((d) => d.textContent).join(' ');
  const topText = () => { const t = first(LI.top) || document.body; return String(t.innerText || t.textContent || '').slice(0, 5000); };

  function liModal() {
    const hasNav = (d) => [...d.querySelectorAll('button')].some((b) => vis(b)
      && (LI.next.test(clean(b.textContent)) || LI.submit.test(clean(b.textContent))
        || LI.next.test(b.getAttribute('aria-label') || '') || LI.submit.test(b.getAttribute('aria-label') || '')));
    const isEA = (d) => d.matches('.jobs-easy-apply-modal, [data-test-modal-id="easy-apply-modal"]') || !!d.querySelector('.jobs-easy-apply-content');
    const cands = [...document.querySelectorAll(LI.modal)].filter(vis);
    for (let i = cands.length - 1; i >= 0; i--) if (isEA(cands[i]) || hasNav(cands[i])) return cands[i];
    if (/\/jobs\/view\/\d+\/apply/.test(location.pathname)) {
      const hasFields = (d) => [...d.querySelectorAll('input:not([type="hidden"]), select, textarea')].some(vis);
      let m = [...document.querySelectorAll('form')].find((x) => vis(x) && (hasNav(x) || hasFields(x)));
      while (m && m.parentElement && m.parentElement !== document.body && !hasNav(m)) m = m.parentElement;
      return m || null;
    }
    return null;
  }

  function liButton(re) {
    const m = liModal();
    if (!m) return null;
    return [...m.querySelectorAll('button, [role="button"]')]
      .filter((b) => vis(b) && !b.disabled && b.getAttribute('aria-disabled') !== 'true')
      .find((b) => re.test(b.getAttribute('aria-label') || '') || re.test(clean(b.textContent))) || null;
  }

  function liErrors() {
    const m = liModal();
    if (!m) return [];
    const out = [];
    for (const e of m.querySelectorAll('.artdeco-inline-feedback--error, [data-test-form-element-error-messages], [role="alert"], '
      + '[class*="error" i], [id$="-error"], [data-test*="error" i]')) {
      const t = clean(e.textContent);
      if (vis(e) && t && t.length < 160 && !e.querySelector('input, select, textarea') && !out.includes(t)) out.push(t);
    }
    for (const f of F.scan(m)) if (f.error && !out.includes(f.error)) out.push(f.error);
    return out;
  }

  const liFingerprint = () => { const m = liModal(); return m ? clean(m.textContent).slice(0, 800) : ''; };
  const liSent = () => LI.sent.test(dialogText()) || (!liModal() && LI.applied.test(topText()));

  async function liDiscard() {
    const m = liModal();
    if (!m) return;
    lastSnap = snapshot();
    const x = m.querySelector("button[aria-label='Dismiss'], button[aria-label='Close'], .artdeco-modal__dismiss");
    if (x) x.click();
    const b = await F.waitFor(() => [...document.querySelectorAll("button[data-control-name='discard_application_confirm_btn'], button")]
      .find((y) => vis(y) && /^\s*discard\s*$/i.test(y.textContent || '')), 2);
    if (b) b.click();
    await sleep(600);
  }

  async function liCloseDialogs() {
    for (let k = 0; k < 3; k++) {
      const d = [...document.querySelectorAll('[role="dialog"], dialog[open], .artdeco-modal')].filter(vis).pop();
      if (!d) return;
      const b = d.querySelector('button[aria-label="Dismiss"], button[aria-label="Close"], .artdeco-modal__dismiss')
        || [...d.querySelectorAll('button')].find((x) => vis(x) && /^(done|not now|close)$/i.test(clean(x.textContent)));
      if (!b) return;
      b.click();
      await sleep(900);
    }
  }

  async function linkedin() {
    if (/^\/(login|checkpoint|authwall|uas|signup)/.test(location.pathname)) {
      return done('needs_you', 'Sign in to LinkedIn in this browser first');
    }
    if (/\/jobs\/view\/\d+\/apply/.test(location.pathname)) {   // the newer layout: the form is its own page
      if (!hello.sites.linkedin) return done('skipped', 'LinkedIn Easy Apply is turned off');
      const m = await F.waitFor(liModal, 20);
      if (!m) return done('error', 'The Easy Apply form did not open');
      return liSteps({ title: job.title, company: job.company, description: '' });
    }
    await F.waitFor(() => first(LI.desc) || document.querySelector(LI.apply) || LI.closed.test(topText()), 20);
    await sleep(1200);
    if (document.querySelector("a[href*='/login'], form.login__form") && !document.querySelector('#global-nav, .global-nav')
        && !document.querySelector(LI.apply)) {
      return done('needs_you', 'Sign in to LinkedIn in this browser first');
    }
    const top = topText();
    if (LI.applied.test(top)) return done('skipped', 'Already applied');
    if (LI.closed.test(top)) return done('skipped', 'No longer accepting applications');
    const d = first(LI.desc);
    const ctx = { title: job.title, company: job.company, description: d ? clean(d.innerText).slice(0, 4000) : '' };

    const btns = [...document.querySelectorAll(LI.apply)].filter(vis);
    const easy = btns.find((b) => !b.disabled && /easy apply/i.test(liLabel(b)));
    if (!easy) {   // "Apply" on the company's own site
      const other = btns.find((b) => /apply/i.test(liLabel(b)))
        || [...document.querySelectorAll('main button, main a')].find((b) => vis(b) && /^apply( now)?$/i.test(clean(b.textContent)));
      if (!other) return done('skipped', 'No Apply button on this job');
      if (!hello.sites.company) return done('skipped', 'Applies on the company site (turned off)');
      let href = other.tagName === 'A' ? other.href : '';
      try {        // LinkedIn wraps the company's link: take the real address out of it
        const u = new URL(href);
        const inner = /linkedin\.com$/.test(u.hostname) ? (u.searchParams.get('url') || u.searchParams.get('redirect') || '') : '';
        if (/^https?:/.test(inner)) href = inner;
      } catch (e) { /* not a link */ }
      if (href && /^https?:/.test(href) && !/linkedin\.com\/jobs\/view/.test(href)) return done('external', '', { url: href });
      await send({ t: 'expect_external' });
      other.click();   // opens the company's site in a new tab: the background follows it
      await sleep(6000);
      return done('skipped', 'The Apply button did not open the company site');
    }
    if (!hello.sites.linkedin) return done('skipped', 'LinkedIn Easy Apply is turned off');

    const href = easy.tagName === 'A' ? (easy.getAttribute('href') || '') : '';
    if (href.includes('/apply') && !liModal()) {
      location.href = new URL(href, location.href).toString();
      return undefined;
    }
    easy.click();
    const opened = await F.waitFor(() => {
      if (LI.limit.test(dialogText())) return 'limit';
      if (liModal()) return 'modal';
      const c = [...document.querySelectorAll('[role="dialog"] button, .artdeco-modal button')]
        .find((x) => vis(x) && /^continue applying$/i.test(clean(x.textContent)));
      if (c) c.click();
      return null;
    }, 15);
    if (opened === 'limit') return done('daily_limit', "LinkedIn's daily Easy Apply limit is reached");
    if (!opened) return done('error', 'The Easy Apply form did not open');
    return liSteps(ctx);
  }

  async function liSteps(ctx) {
    let same = 0;
    for (let step = 0; step < 15; step++) {
      if (!liModal()) {
        if (liSent()) { await liCloseDialogs(); return done('applied'); }
        return done('error', 'The form closed unexpectedly');
      }
      if (LI.limit.test(dialogText())) { await liDiscard(); return done('daily_limit', "LinkedIn's daily Easy Apply limit is reached"); }
      await F.uploadCv(liModal, false);
      qa.push(...await F.fill(liModal, ctx, false));
      await sleep(900);

      if (liButton(LI.submit)) {
        if (noSubmit) { await liDiscard(); return done('dry_run', 'Stopped before Submit (test run)'); }
        const m = liModal();
        const cb = m.querySelector('#follow-company-checkbox, input[type="checkbox"][id*="follow-company"]');
        if (cb && cb.checked) (document.querySelector('label[for="' + CSS.escape(cb.id) + '"]') || cb).click();
        await sleep(700);
        await send({ t: 'submitting' });
        liButton(LI.submit).click();
        const r = await F.waitFor(() => (liSent() ? 'ok' : (LI.limit.test(dialogText()) ? 'limit' : null)), 15);
        if (r === 'limit') { await liDiscard(); return done('daily_limit', "LinkedIn's daily Easy Apply limit is reached"); }
        if (!r && liModal() && liButton(LI.submit)) {
          const errs = liErrors();
          await liDiscard();
          return done('stuck', errs.length ? 'Submit failed: ' + errs.join(' / ').slice(0, 200) : 'Submit did not complete');
        }
        await sleep(1200);
        await liCloseDialogs();
        return done('applied', r ? '' : 'Submitted (no confirmation seen)');
      }
      const next = liButton(LI.next);
      if (!next) { await liDiscard(); return done('stuck', 'No Next / Review / Submit button found'); }
      const before = liFingerprint();
      next.click();
      await sleep(1800);

      let errs = liErrors();
      for (let attempt = 1; errs.length && attempt <= 3; attempt++) {   // LinkedIn rejected something: repair and retry
        log('LinkedIn rejected: ' + errs.join(' / ').slice(0, 150) + ' -> fixing (try ' + attempt + '/3)');
        let fixed = false;
        if (/resume|cv\b|upload|file/i.test(errs.join(' '))) fixed = await F.uploadCv(liModal, true);
        const filled = await F.fill(liModal, ctx, true);
        qa.push(...filled);
        if (!filled.length && !fixed) break;
        await sleep(800);
        const again = liButton(LI.next);
        if (!again) break;
        again.click();
        await sleep(1800);
        errs = liErrors();
      }
      if (errs.length) { await liDiscard(); return done('stuck', 'Form error: ' + errs.join(' / ').slice(0, 200)); }
      if (liFingerprint() === before) {
        same += 1;
        if (same >= 3) {
          const html = snapshot();
          const open = F.scan(liModal()).filter((f) => (f.value !== undefined ? !f.value : (f.options ? !f.options.some((o) => o.checked) && !f.selected : !f.selected)))
            .map((f) => f.question).slice(0, 3).join(' | ');
          await liDiscard();
          return done('stuck', 'The form did not move to the next step' + (open ? '. Not answered: ' + open.slice(0, 220) : ''), { html });
        }
      } else same = 0;
    }
    await liDiscard();
    return done('stuck', 'Too many form steps');
  }

  // ------------------------------------------------------------------ company sites and Wuzzuf (any application form)
  const SUBMIT_RE = /^(submit( my| your)?( application)?|send( my)? application|apply( now)?|apply for (this )?(job|position)|complete application|finish|send)$/i;
  const NEXT_RE = /^(next( step)?|continue|save (and|&) continue|proceed|review)$/i;
  const START_RE = /^(apply( now| for (this )?(job|position)| here| online| manually| for job)?|i'?m interested|start( your| my)? application|apply with (resume|cv))$/i;
  const NOT_RE = /linkedin|indeed|google|facebook|seek|xing|sign in|log ?in|save for later|share|refer|alert/i;
  const THANKS_RE = /thank you for (applying|your application|your interest)|application (has been |was |is )?(received|submitted|sent|complete)|we('ve| have) received your application|successfully (applied|submitted)|applied successfully|you('ve| have) (already )?applied/i;

  function formRoot() {
    const forms = [...document.querySelectorAll('form')].filter(vis)
      .map((f) => ({ f, n: [...f.querySelectorAll('input:not([type="hidden"]), select, textarea')].filter(vis).length }))
      .filter((x) => x.n >= 2 && !/search|login|sign-?in|subscribe|newsletter/i.test((x.f.id || '') + ' ' + (x.f.className || '') + ' ' + (x.f.getAttribute('action') || '')))
      .sort((a, b) => b.n - a.n);
    if (forms.length) return forms[0].f;
    // no <form>: a visible block with real fields in it (search and filter boxes of a job page do not count)
    const real = (i) => vis(i) && !i.readOnly && (i.type || '') !== 'search'
      && !/search|keyword|query|filter|location|copy|share|^q$/i.test(`${i.id} ${i.name} ${i.getAttribute('placeholder') || ''} ${i.getAttribute('title') || ''} ${i.getAttribute('aria-label') || ''}`);
    const main = [...document.querySelectorAll('[role="dialog"], dialog[open], main, #main, #content')].find(vis) || document.body;
    return [...main.querySelectorAll('input:not([type="hidden"]), select, textarea')].filter(real).length >= 3 ? main : null;
  }

  const siteErrors = () => {
    const r = formRoot();
    if (!r) return [];
    return F.scan(r).filter((f) => f.error).map((f) => f.question + ': ' + f.error);
  };

  /** The page is not understood: show the AI what can be clicked and click what it picks. Returns true when it clicked. */
  async function guide(goal) {
    const els = [...document.querySelectorAll('button, a[href], [role="button"], input[type="submit"], input[type="button"]')]
      .filter((e) => vis(e) && !e.disabled && e.getAttribute('aria-disabled') !== 'true')
      .map((e) => ({ e, t: clean(e.innerText || e.value || e.getAttribute('aria-label') || e.getAttribute('title') || '').slice(0, 80) }))
      .filter((x) => x.t && !/sign ?in|log ?in|register|create (an )?account|google|facebook|linkedin|apple|share|cookie|privacy|terms|back|cancel/i.test(x.t))
      .slice(0, 70);
    if (!els.length) return false;
    const r = await send({ t: 'guide', goal, title: document.title, host: location.hostname, text: clean(pageText()).slice(0, 1500),
      elements: els.map((x, i) => (i + 1) + ' | ' + x.e.tagName.toLowerCase() + ' | ' + x.t) });
    const pick = r && r.click ? els[Number(r.click) - 1] : null;
    if (!pick) { if (r && r.reason) log('AI: nothing to click (' + r.reason + ')'); return false; }
    if (noSubmit && /submit|send|finish|complete/i.test(pick.t)) { await done('dry_run', 'Stopped before "' + pick.t + '" (test run)'); return 'ended'; }
    log('AI chose "' + pick.t + '"');
    await send({ t: 'step' });
    F.click(pick.e);
    return true;
  }

  async function site(kind) {
    await sleep(1500);
    const steps = hello.steps || 0;
    if (THANKS_RE.test(pageText()) && steps > 0) return done('applied');
    if (kind === 'wuzzuf' && /\/(login|register)/.test(location.pathname)) return done('needs_you', 'Sign in to Wuzzuf in this browser first');
    if (steps > 8) return done('stuck', 'Too many pages in this application');
    const ctx = { title: job.title, company: job.company, description: clean(pageText()).slice(0, 3000) };

    // "Autofill with resume" comes first where a site offers it: the site reads the CV and fills most of the form itself
    const RESUME_RE = /^(autofill|auto-fill|apply|upload|import|parse|continue|start)( with| from| using| your| my)*( resume| cv)$|^(upload|attach|import) (a |your |my )?(resume|cv)$/i;
    const findStart = () => F.buttons(document, RESUME_RE, NOT_RE)[0] || F.buttons(document, START_RE, NOT_RE)[0]
      || [...document.querySelectorAll('a')].find((a) => vis(a) && (RESUME_RE.test(clean(a.innerText)) || START_RE.test(clean(a.innerText)))
        && !NOT_RE.test(clean(a.innerText)));
    const fileInput = () => [...document.querySelectorAll('input[type="file"]')]
      .find((i) => !/cover|letter|photo|image|avatar|picture/i.test(`${i.id} ${i.name} ${i.getAttribute('aria-label') || ''} ${i.accept || ''}`)) || null;
    const signIn = () => [...document.querySelectorAll('input[type="password"]')].some(vis)
      || (!formRoot() && F.buttons(document, /^(sign in|log ?in|create (an )?account|sign in with email|register)$/i).length > 0
        && /\b(sign in|log in|create (an )?account)\b/i.test(pageText().slice(0, 4000)) && /apply|login|signin|sign-in|auth/i.test(location.pathname));
    const area = () => formRoot() || (fileInput() ? (document.querySelector('[role="dialog"], main, #main, #content') || document.body) : null);
    const NEEDS_ACCOUNT = 'needs an account: sign in on ' + location.hostname + ' once, then it can apply there';
    await F.waitFor(() => area() || signIn() || findStart(), 15);      // pages that build themselves take a while
    if (!area() && signIn()) return done('needs_you', 'This site ' + NEEDS_ACCOUNT);
    let root = area();
    if (!root) {   // the job page: find its Apply button
      if (kind === 'wuzzuf' && /you (have )?(already )?applied|applied on/i.test(pageText())) return done('skipped', 'Already applied');
      const start = findStart();
      if (!start) {
        const before = location.href;
        const g = await guide('Open the application form for this job (the Apply button or link).');
        if (g === 'ended') return undefined;
        if (g) await F.waitFor(() => area() || signIn(), 12);
        if (!area()) {
          if (signIn()) return done('needs_you', 'This site ' + NEEDS_ACCOUNT);
          if (g && location.href !== before) return undefined;        // a new page is loading: the driver starts again there
          return done('stuck', 'No application form found on ' + location.hostname);
        }
      } else {
      if (RESUME_RE.test(clean(start.innerText || start.value || ''))) log('Using "' + clean(start.innerText || start.value || '') + '"');
      await send({ t: 'step' });
      const before = location.href;
      F.click(start);
      await F.waitFor(() => area() || signIn(), 15);
      root = area();
      if (!root) {
        if (signIn()) return done('needs_you', 'This site ' + NEEDS_ACCOUNT);
        if (location.href !== before) return undefined;   // a new page is loading: the driver starts again there
        const g = await guide('The Apply button was clicked but no application form appeared. Choose what opens the application form.');
        if (g === 'ended') return undefined;
        if (g) await F.waitFor(() => area() || signIn(), 12);
        if (!area()) {
          if (signIn()) return done('needs_you', 'This site ' + NEEDS_ACCOUNT);
          if (g && location.href !== before) return undefined;
          return done('stuck', 'The Apply button did not open a form');
        }
      }
      }
      root = area();
    }
    if (root.querySelector('input[type="password"]')) return done('needs_you', 'This site needs an account: sign in there first');

    for (let round = 0; round < 6; round++) {
      if (round > 0 && THANKS_RE.test(pageText())) return done('applied');      // the last click did send it
      if (await F.uploadCv(area, false)) { log('CV attached'); await sleep(4000); }     // the site reads the CV and fills what it can
      qa.push(...await F.fill(area, ctx, round > 0));
      await sleep(800);
      if (hasCaptcha()) return done('needs_you', 'This form has a CAPTCHA: finish it yourself', { keepTab: true });
      const r = area() || document;
      const submit = F.buttons(r, SUBMIT_RE, NOT_RE)[0];
      const next = F.buttons(r, NEXT_RE, NOT_RE)[0];
      const btn = next || submit;
      if (!btn) {
        const g = await guide('The application form is filled. Choose the button that continues to the next step or submits the application.');
        if (g === 'ended') return undefined;
        if (!g) return done('stuck', 'No Next or Submit button found');
        await sleep(3500);
        if (THANKS_RE.test(pageText()) || !area()) return done('applied', THANKS_RE.test(pageText()) ? '' : 'Submitted (no confirmation seen)');
        continue;
      }
      if (btn === submit && noSubmit) return done('dry_run', 'Stopped before Submit (test run)');
      const before = clean((area() || document.body).textContent).slice(0, 600);
      await send({ t: btn === submit ? 'submitting' : 'step' });
      F.click(btn);
      const clicked = Date.now();
      const moved = await F.waitFor(() => {
        if (THANKS_RE.test(pageText())) return 'thanks';
        if (Date.now() - clicked < 2500) return null;       // give the page a moment before reading errors
        if (signIn() && !formRoot()) return 'signin';
        const fr = area();
        if (!fr) return 'gone';
        if (siteErrors().length) return 'errors';
        return clean(fr.textContent).slice(0, 600) !== before ? 'moved' : null;
      }, 10);
      if (moved === 'thanks') return done('applied');
      if (moved === 'signin') return done('needs_you', 'This site ' + NEEDS_ACCOUNT);
      if (moved === 'gone') {
        await sleep(2500);
        if (THANKS_RE.test(pageText()) || btn === submit) return done('applied', THANKS_RE.test(pageText()) ? '' : 'Submitted (no confirmation seen)');
        return done('stuck', 'The form disappeared before it was sent');
      }
      if (moved === 'errors' && round >= 3) return done('stuck', 'Form error: ' + siteErrors().join(' / ').slice(0, 200));
      if (!moved && btn === submit) return done('stuck', 'Submit did not complete');
    }
    return done('stuck', 'The form did not finish');
  }

  try {
    if (hello.kind === 'linkedin') await linkedin();
    else await site(hello.kind);
  } catch (e) {
    done('error', String((e && e.message) || e).slice(0, 200));
  }
})();
