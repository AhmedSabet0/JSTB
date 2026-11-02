// LinkedIn page scripts. Each section is the body of a function(arg) that runs in the page.
// '//# NAME' starts a section; HELPERS is put in front of every section.

//# HELPERS
const SEL_GROUP = '[data-test-form-element], .fb-dash-form-element, .jobs-easy-apply-form-element, .jobs-easy-apply-form-section__grouping';
const ERR_STRONG = '.artdeco-inline-feedback--error, [data-test-form-element-error-messages]';
const ERR_TEXT = /please|required|invalid|enter a|select an|make a selection|must be|is not valid|valid (?:answer|number|email|phone)/i;
const vis = (el) => {
  if (!el || !el.isConnected) return false;
  const st = getComputedStyle(el);
  if (st.display === 'none' || st.visibility === 'hidden') return false;
  return el.getClientRects().length > 0;
};
const clean = (s) => {
  let t = String(s || '').replace(/\s+/g, ' ').trim();
  const n = t.length;
  if (n >= 4) {
    if (n % 2 === 0 && t.slice(0, n / 2) === t.slice(n / 2)) t = t.slice(0, n / 2);
    else if (n % 2 === 1 && t[(n - 1) / 2] === ' ' && t.slice(0, (n - 1) / 2) === t.slice((n + 1) / 2)) t = t.slice(0, (n - 1) / 2);
  }
  return t.replace(/\s*\*\s*$/, '').replace(/\s*\bRequired\s*$/i, '').trim();
};
const txt = (node) => {
  if (!node) return '';
  const hidden = node.querySelector ? node.querySelector('span[aria-hidden="true"]') : null;
  const a = hidden ? clean(hidden.textContent) : '';
  return a.length >= 2 ? a : clean(node.textContent);
};
const labelFor = (el) => (el && el.id ? document.querySelector('label[for="' + CSS.escape(el.id) + '"]') : null);
const firstEl = (list) => {
  for (const s of list) { const el = document.querySelector(s); if (el && el.textContent.trim()) return el; }
  return null;
};
const modalEl = () => document.querySelector('[data-aa-modal="1"]');
const dialogText = (sel) => [...document.querySelectorAll(sel)].filter(vis).map((d) => d.textContent).join(' ');
const errorsIn = (root) => {
  const out = [];
  root.querySelectorAll(ERR_STRONG + ', [role="alert"], [id$="-error"]').forEach((e) => {
    const t = clean(e.textContent);
    if (!t || t.length > 200 || !vis(e)) return;
    if (!e.matches(ERR_STRONG) && !ERR_TEXT.test(t)) return;
    if (!out.includes(t)) out.push(t);
  });
  return out;
};
// Newer layout: the message sits in an element linked with aria-describedby, next to a "16/20" counter.
const isCounter = (t) => /^\d+\s*\/\s*\d+$/.test(t) || /^\d+ of \d+ characters?$/i.test(t);
const leafTexts = (root) => {
  const leaves = [...root.querySelectorAll('*')].filter((x) => !x.children.length);
  return (leaves.length ? leaves : [root]).filter(vis).map((x) => clean(x.textContent)).filter(Boolean);
};
const describedBy = (el) => (el.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean)
  .map((id) => document.getElementById(id)).filter(Boolean);
const describedError = (el) => {
  for (const d of describedBy(el)) {
    if (!vis(d)) continue;
    const msg = leafTexts(d).filter((t) => !isCounter(t)).join(' ');
    if (msg && msg.length <= 200 && (ERR_TEXT.test(msg) || /error/i.test(d.id))) return msg;
  }
  return '';
};
const counterMax = (el) => {
  for (const d of describedBy(el)) {
    for (const t of leafTexts(d)) {
      const m = t.match(/^\d+\s*\/\s*(\d+)$/) || t.match(/^\d+ of (\d+) characters?$/i);
      if (m) return Number(m[1]);
    }
  }
  return 0;
};
const groupOf = (el) => el.closest(SEL_GROUP) || el.closest('fieldset') || el.parentElement;
const textAbove = (el) => {  // the <p> question just above a field (newer layout)
  let n = el;
  for (let k = 0; k < 4 && n && n !== document.body; k++, n = n.parentElement) {
    let s = n.previousElementSibling;
    while (s && !clean(s.textContent)) s = s.previousElementSibling;
    if (s) {
      const t = clean(s.textContent);
      if (t && t.length < 300 && !ERR_TEXT.test(t) && !isCounter(t)) return t;
      return '';
    }
  }
  return '';
};
const questionFor = (el) => {
  if (el.tagName === 'FIELDSET' || el.getAttribute('role') === 'radiogroup') {
    const t = el.querySelector('legend')
      || el.querySelector('[data-test-form-builder-radio-button-form-component__title], .fb-dash-form-element__label');
    if (t && txt(t)) return txt(t);
    const boxes = [...el.querySelectorAll('input[type="radio"], input[type="checkbox"]')];
    const al = boxes.length ? boxes[0].getAttribute('aria-label') : '';
    if (al && boxes.length > 1 && boxes.every((b) => b.getAttribute('aria-label') === al)) return clean(al);
    const above = textAbove(el);
    if (above) return above;
  }
  const l = labelFor(el);
  if (l && txt(l)) return txt(l);
  const al = el.getAttribute('aria-label');
  if (al) return clean(al);
  const g = groupOf(el);
  const gl = g ? [...g.querySelectorAll('label, legend, .fb-dash-form-element__label')].find((x) => txt(x)) : null;
  if (gl) return txt(gl);
  return textAbove(el) || clean(el.getAttribute('placeholder') || '');
};
const optionLabel = (i) => {
  const l = labelFor(i) || i.closest('label');
  if (l && txt(l)) return txt(l);
  let p = i.parentElement;  // newer layout: empty <label>, the text is in a sibling <p>
  for (let k = 0; k < 4 && p; k++, p = p.parentElement) {
    if (p.matches('fieldset, [role="radiogroup"]')) break;
    const t = clean(p.textContent);
    if (t && t.length <= 150) return t;
  }
  const v = i.value && i.value !== 'on' ? i.value : '';
  return clean(v || i.getAttribute('aria-label') || '');
};
const fieldError = (el) => {
  const own = describedError(el);
  if (own) return own;
  if (el.tagName === 'FIELDSET') {
    for (const b of el.querySelectorAll('input')) { const e = describedError(b); if (e) return e; }
  }
  const g = el.tagName === 'FIELDSET' ? ((el.parentElement && el.parentElement.closest(SEL_GROUP)) || el) : groupOf(el);
  if (!g) return '';
  const e = errorsIn(g)[0];
  if (e) return e;
  return el.getAttribute && el.getAttribute('aria-invalid') === 'true' ? 'Invalid value' : '';
};
const aboutJob = () => [...document.querySelectorAll('h1, h2, h3, h4, strong, span')]
  .find((e) => /^about the job$/i.test((e.textContent || '').trim()));

//# auth
if (document.querySelector(arg.inSel)) return 'in';
if (document.querySelector(arg.outSel)) return 'out';
return 'unknown';

//# exists
return !!document.querySelector(arg);

//# scroll
const card = document.querySelector('[data-occludable-job-id], [data-job-id], a[href*="currentJobId="], [data-view-name*="job-card"]');
let el = card ? card.parentElement : null;
while (el && el !== document.body) {
  const st = getComputedStyle(el);
  if (/(auto|scroll)/.test(st.overflowY) && el.scrollHeight > el.clientHeight + 40) { el.scrollBy(0, 700); return true; }
  el = el.parentElement;
}
document.querySelectorAll(arg).forEach((e) => { if (e.scrollBy) e.scrollBy(0, 700); });
window.scrollBy(0, 700);
return false;

//# ids
const ids = [];
const add = (id) => { if (id && /^\d+$/.test(id) && !ids.includes(id)) ids.push(id); };
document.querySelectorAll('[data-occludable-job-id]').forEach((el) => add(el.getAttribute('data-occludable-job-id')));
document.querySelectorAll('[data-job-id]').forEach((el) => add(el.getAttribute('data-job-id')));
document.querySelectorAll('a[href]').forEach((a) => {
  const h = a.getAttribute('href') || '';
  const m = h.match(/\/jobs\/view\/(?:[^\/?"']*?-)?(\d+)/) || h.match(/[?&]currentJobId=(\d+)/);
  if (m) add(m[1]);
});
const html = document.documentElement.outerHTML;
for (const re of [/currentJobId=(\d{6,})/g, /jobPosting(?::|%3A|\()(\d{6,})/g, /\/jobs\/view\/(\d{6,})/g,
                  /job-?id["'=:\s]+["']?(\d{6,})/gi]) {
  let m;
  while ((m = re.exec(html))) add(m[1]);
}
return ids;

//# guest
try {
  const r = await fetch('/jobs-guest/jobs/api/seeMoreJobPostings/search?' + arg, { credentials: 'include' });
  return r.ok ? await r.text() : '';
} catch (e) { return ''; }

//# title_ready
return !!firstEl(arg) || /\|.+\|/.test(document.title);

//# desc_ready
const d = firstEl(arg); return !!(d && d.textContent.trim().length > 50) || !!aboutJob();

//# read_job
const parts = document.title.replace(/^\(\d+\)\s*/, '').split('|').map((s) => s.trim());
const t = firstEl(arg.title), c = firstEl(arg.company);
let title = t ? clean(t.textContent) : '';
if (!title) title = parts.length >= 3 ? parts[0] : '';
if (!title) { const g = firstEl(arg.title_generic); title = g ? clean(g.textContent) : (parts[0] || ''); }
const d = firstEl(arg.description);
let text = d ? String(d.innerText || d.textContent || '') : '';
let source = 'selector';
if (text.trim().length < 100) {
  const h = aboutJob();
  let box = h ? h.parentElement : null;
  for (let i = 0; i < 5 && box && String(box.innerText || '').trim().length < 200; i++) box = box.parentElement;
  if (box && String(box.innerText || '').trim().length >= 100) { text = String(box.innerText); source = 'heading'; }
}
if (text.trim().length < 100) {
  const m = document.querySelector('main') || document.body;
  text = String(m.innerText || m.textContent || '');
  source = 'page';
}
return {
  title,
  company: (c && clean(c.textContent)) || (parts.length >= 3 ? parts[1] : ''),
  description: text.replace(/\n{3,}/g, '\n\n').trim().slice(0, 8000),
  source,
};

//# job_state
const lab = (b) => (b.getAttribute('aria-label') || '') + ' ' + (b.textContent || '');
const btns = [...document.querySelectorAll(arg.sel)].filter(vis);
const topEl = firstEl(arg.top) || document.body;
const top = String(topEl.innerText || topEl.textContent || '').slice(0, 5000);
const plainApply = [...document.querySelectorAll('main button, main a')]
  .some((b) => vis(b) && /^apply( on company website)?$/i.test(clean(b.textContent)));
return {
  easy: btns.some((b) => !b.disabled && /easy apply/i.test(lab(b))),
  other: plainApply || btns.some((b) => /apply/i.test(b.textContent || '') && !/easy apply/i.test(lab(b))),
  applied: new RegExp(arg.applied, 'i').test(top),
  closed: new RegExp(arg.closed, 'i').test(top),
};

//# tag_easy_apply
document.querySelectorAll('[data-aa-ea]').forEach((e) => e.removeAttribute('data-aa-ea'));
const lab = (b) => (b.getAttribute('aria-label') || '') + ' ' + (b.textContent || '');
const b = [...document.querySelectorAll(arg)].find((x) => vis(x) && !x.disabled && /easy apply/i.test(lab(x)));
if (!b) return null;
b.setAttribute('data-aa-ea', '1');
return { href: b.tagName === 'A' ? (b.getAttribute('href') || '') : '' };

//# tag_modal
document.querySelectorAll('[data-aa-modal]').forEach((e) => e.removeAttribute('data-aa-modal'));
const navRe = /^(next|continue|continue to next step|review|review your application|submit|submit application)$/i;
const hasNav = (d) => [...d.querySelectorAll('button')].some((b) => vis(b)
  && (navRe.test(clean(b.textContent)) || navRe.test((b.getAttribute('aria-label') || '').trim())));
const hasFields = (d) => [...d.querySelectorAll('input:not([type="hidden"]), select, textarea')].some(vis);
const isEA = (d) => d.matches('.jobs-easy-apply-modal, [data-test-modal-id="easy-apply-modal"]')
  || !!d.querySelector('.jobs-easy-apply-content');
const cands = [...document.querySelectorAll(arg)].filter(vis);
let m = null;
for (let i = cands.length - 1; i >= 0; i--) {
  if (isEA(cands[i]) || hasNav(cands[i])) { m = cands[i]; break; }
}
if (!m && /\/jobs\/view\/\d+\/apply/.test(location.pathname)) {
  const f = [...document.querySelectorAll('form')].find((x) => vis(x) && (hasNav(x) || hasFields(x)));
  if (f) {
    m = f;
    while (m.parentElement && m.parentElement !== document.body && !hasNav(m)) m = m.parentElement;
  }
}
if (!m) return false;
m.setAttribute('data-aa-modal', '1');
return true;

//# limit
const t = dialogText('[role="dialog"], dialog[open], .artdeco-modal, .artdeco-toast-item, .artdeco-inline-feedback');
return new RegExp(arg, 'i').test(t);

//# tag_continue
document.querySelectorAll('[data-aa-continue]').forEach((e) => e.removeAttribute('data-aa-continue'));
const b = [...document.querySelectorAll('[role="dialog"] button, dialog[open] button, .artdeco-modal button')]
  .find((x) => vis(x) && /^(continue applying)$/i.test(clean(x.textContent)));
if (!b) return false;
b.setAttribute('data-aa-continue', '1');
return true;

//# errors
const m = modalEl();
if (!m) return [];
const out = [];
const push = (q, t) => { const s = q && q !== t ? q + ': ' + t : t; if (s && !out.includes(s)) out.push(s); };
m.querySelectorAll(ERR_STRONG + ', [role="alert"], [id$="-error"]').forEach((e) => {
  const t = clean(e.textContent);
  if (!t || t.length > 200 || !vis(e)) return;
  if (!e.matches(ERR_STRONG) && !ERR_TEXT.test(t)) return;
  const g = e.closest(SEL_GROUP) || e.closest('fieldset') || e.parentElement;
  const l = g ? [...g.querySelectorAll('label, legend')].find((x) => txt(x)) : null;
  push(l ? txt(l) : '', t);
});
// newer layout: messages linked to the field with aria-describedby ("Invalid input" under the box)
m.querySelectorAll('input:not([type="hidden"]), textarea, select, fieldset').forEach((el) => {
  const t = describedError(el);
  if (!t) return;
  const box = el.matches('input[type="radio"], input[type="checkbox"]') ? (el.closest('fieldset') || el) : el;
  push(questionFor(box), t);
});
if (!out.length && [...m.querySelectorAll('[aria-invalid="true"]')].some(vis)) out.push('Invalid value');
return out;

//# fingerprint
const m = modalEl(); return m ? clean(m.textContent).slice(0, 800) : '';

//# scan
const root = modalEl();
if (!root) return [];
window.__aaN = window.__aaN || 0;
const tag = (el) => { window.__aaN += 1; const id = 'a' + window.__aaN; el.setAttribute('data-aa', id); return id; };
const skip = /follow-company|top-choice|top choice/i;
const out = [];
const seen = new Set();
for (const el of root.querySelectorAll('input, textarea, select')) {
  const type = (el.getAttribute('type') || '').toLowerCase();
  if (['hidden', 'file', 'submit', 'button', 'search', 'image', 'reset'].includes(type)) continue;
  if (skip.test(el.id || '') || skip.test(el.getAttribute('name') || '')) continue;
  if (type === 'radio' || type === 'checkbox') {
    const group = el.closest('fieldset') || groupOf(el);
    if (!group || seen.has(group)) continue;
    seen.add(group);
    if (!vis(group)) continue;
    const inputs = [...group.querySelectorAll('input[type="radio"], input[type="checkbox"]')]
      .filter((i) => !skip.test(i.id || '') && !skip.test(optionLabel(i)));
    if (!inputs.length) continue;
    const options = inputs.map((i) => {
      const id = tag(i);
      const l = labelFor(i) || i.closest('label');
      const usable = !!l && vis(l);
      if (usable) l.setAttribute('data-aa', id + 'l');
      return { id, label: optionLabel(i), checked: !!i.checked, hasLabel: usable };
    });
    let kind = inputs[0].type === 'radio' ? 'radio' : (inputs.length === 1 ? 'single_checkbox' : 'checkbox');
    if (options.every((o) => /\.(pdf|docx?)\b/i.test(o.label))) kind = 'resume';
    const required = inputs.some((i) => i.required || i.getAttribute('aria-required') === 'true')
      || group.tagName === 'FIELDSET' || /\*|required/i.test(group.textContent || '');
    out.push({ kind, question: questionFor(group), options, error: fieldError(group), required });
    continue;
  }
  if (!vis(el) || el.disabled || el.readOnly) continue;
  const id = tag(el);
  if (el.tagName === 'SELECT') {
    const isPh = (o) => !o || o.value === '' || /^(select( an)? option|select|choose)/i.test(o.textContent.trim());
    const cur = el.options[el.selectedIndex];
    out.push({
      kind: 'select', id, question: questionFor(el), error: fieldError(el),
      selected: isPh(cur) ? '' : clean(cur.textContent),
      options: [...el.options].map((o, index) => ({ label: clean(o.textContent), index, ph: isPh(o) }))
        .filter((o) => !o.ph && o.label),
    });
    continue;
  }
  let kind = 'text';
  if (el.tagName === 'TEXTAREA') kind = 'textarea';
  else if (/numeric/i.test(el.id || '') || type === 'number' || /numeric|decimal/i.test(el.getAttribute('inputmode') || '')) kind = 'numeric';
  const typeahead = el.getAttribute('role') === 'combobox' || !!el.getAttribute('aria-autocomplete')
    || !!el.closest('.search-basic-typeahead, .basic-typeahead, [data-test-single-typeahead-entity-form-component]');
  out.push({ kind, id, question: questionFor(el), value: el.value || '', error: fieldError(el),
    placeholder: el.getAttribute('placeholder') || '', typeahead,
    maxlen: el.maxLength > 0 ? el.maxLength : counterMax(el) });
}
return out;

//# tag_file
const m = modalEl();
if (!m) return null;
document.querySelectorAll('[data-aa-file]').forEach((e) => e.removeAttribute('data-aa-file'));
const inp = [...m.querySelectorAll('input[type="file"]')]
  .find((i) => !/cover/i.test(`${i.id} ${i.name} ${i.getAttribute('aria-label') || ''}`));
if (!inp) return { found: false };
inp.setAttribute('data-aa-file', '1');
return { found: true, hasResume: /\.(pdf|docx?)\b/i.test(m.textContent || '') };

//# tag_follow
const m = modalEl() || document;
const cb = m.querySelector('#follow-company-checkbox') || m.querySelector('input[type="checkbox"][id*="follow-company"]');
if (!cb || !cb.checked) return false;
(labelFor(cb) || cb).setAttribute('data-aa-follow', '1');
return true;

//# sent
if (new RegExp(arg.sent, 'i').test(dialogText('[role="dialog"], dialog[open], .artdeco-modal, .artdeco-toast-item'))) return true;
const m = modalEl();
const topEl = firstEl(arg.top) || document.body;
return !(m && vis(m)) && new RegExp(arg.applied, 'i').test(String(topEl.innerText || ''));

//# tag_close
document.querySelectorAll('[data-aa-close]').forEach((e) => e.removeAttribute('data-aa-close'));
const d = [...document.querySelectorAll('[role="dialog"], dialog[open], .artdeco-modal')].filter(vis).pop();
if (!d) return false;
const b = d.querySelector('button[aria-label="Dismiss"], button[aria-label="Close"], .artdeco-modal__dismiss')
  || [...d.querySelectorAll('button')].find((x) => vis(x) && /^(done|not now|close)$/i.test(clean(x.textContent)));
if (!b) return false;
b.setAttribute('data-aa-close', '1');
return true;

//# click
// arg: CSS selector. Scrolls the element into view and clicks it like a person (pointer, mouse, click).
const el = document.querySelector(arg);
if (!el || !el.isConnected) return false;
try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) { /* old engines */ }
const r = el.getBoundingClientRect();
const o = { bubbles: true, cancelable: true, composed: true, view: window, button: 0,
  clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
const po = Object.assign({ pointerId: 1, pointerType: 'mouse', isPrimary: true }, o);
try { el.dispatchEvent(new PointerEvent('pointerover', po)); el.dispatchEvent(new PointerEvent('pointerdown', po)); } catch (e) { /* no PointerEvent */ }
el.dispatchEvent(new MouseEvent('mousedown', o));
try { if (el.focus) el.focus({ preventScroll: true }); } catch (e) { /* not focusable */ }
try { el.dispatchEvent(new PointerEvent('pointerup', po)); } catch (e) { /* no PointerEvent */ }
el.dispatchEvent(new MouseEvent('mouseup', o));
el.click();
return true;

//# rect
// arg: CSS selector. Scrolls the element into view and returns where to tap it (CSS pixels of the visible area),
// {covered: true} when something else is on top of it, or null when it is not on the page.
const el = document.querySelector(arg);
if (!el || !vis(el)) return null;
try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) { /* old engines */ }
const r = el.getBoundingClientRect();
if (r.width < 2 || r.height < 2) return null;
const x = r.left + r.width / 2;
const y = r.top + r.height / 2;
const top = document.elementFromPoint(x, y);
if (!top || !(top === el || el.contains(top))) return { covered: true };
const vv = window.visualViewport;
return { x: x - (vv ? vv.offsetLeft : 0), y: y - (vv ? vv.offsetTop : 0), vw: vv ? vv.width : window.innerWidth };

//# type
// arg: {sel, value, typeahead}. Types into a text box the way React expects (native setter + input events).
const el = document.querySelector(arg.sel);
if (!el) return false;
try { el.scrollIntoView({ block: 'center' }); } catch (e) { /* old engines */ }
el.focus();
const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
setter.call(el, '');
el.dispatchEvent(new Event('input', { bubbles: true }));
const v = String(arg.value);
const steps = arg.typeahead ? [...v].map((_, i) => [...v].slice(0, i + 1).join('')) : [v];
for (const part of steps) {
  const ch = part.slice(-1);
  el.dispatchEvent(new KeyboardEvent('keydown', { key: ch, bubbles: true }));
  setter.call(el, part);
  el.dispatchEvent(new InputEvent('input', { bubbles: true, data: ch, inputType: 'insertText' }));
  el.dispatchEvent(new KeyboardEvent('keyup', { key: ch, bubbles: true }));
}
el.dispatchEvent(new Event('change', { bubbles: true }));
if (!arg.typeahead) el.blur();
return true;

//# select
// arg: {sel, index}. Picks an option of a <select>.
const el = document.querySelector(arg.sel);
if (!el || !el.options[arg.index]) return false;
try { el.scrollIntoView({ block: 'center' }); } catch (e) { /* old engines */ }
el.focus();
Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(el, el.options[arg.index].value);
el.selectedIndex = arg.index;
el.dispatchEvent(new Event('input', { bubbles: true }));
el.dispatchEvent(new Event('change', { bubbles: true }));
el.blur();
return el.selectedIndex === arg.index;

//# check
// arg: {id, hasLabel}. Ticks a radio button or checkbox found by scan (clicks its label when it has one).
const inp = document.querySelector('[data-aa="' + arg.id + '"]');
if (!inp) return false;
const fire = (el) => {
  const r = el.getBoundingClientRect();
  const o = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
  el.dispatchEvent(new MouseEvent('mousedown', o));
  el.dispatchEvent(new MouseEvent('mouseup', o));
  el.click();
};
try { (inp.closest('fieldset') || inp).scrollIntoView({ block: 'center' }); } catch (e) { /* old engines */ }
const lab = arg.hasLabel ? document.querySelector('[data-aa="' + arg.id + 'l"]') : null;
if (lab && vis(lab)) fire(lab);
if (!inp.checked) fire(inp);
return !!inp.checked;

//# upload
// arg: {sel, name, mime, b64}. Puts the CV into a file input without a file dialog.
const inp = document.querySelector(arg.sel);
if (!inp) return 0;
const bin = atob(arg.b64);
const bytes = new Uint8Array(bin.length);
for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
const dt = new DataTransfer();
dt.items.add(new File([bytes], arg.name, { type: arg.mime, lastModified: Date.now() }));
inp.files = dt.files;
inp.dispatchEvent(new Event('input', { bubbles: true }));
inp.dispatchEvent(new Event('change', { bubbles: true }));
return inp.files.length;

//# button
// arg: [[ariaLabel, nameRegex], ...]. Tags the first visible, enabled form button that matches.
document.querySelectorAll('[data-aa-btn]').forEach((e) => e.removeAttribute('data-aa-btn'));
const m = modalEl();
if (!m) return false;
const btns = [...m.querySelectorAll('button, [role="button"]')]
  .filter((b) => vis(b) && !b.disabled && b.getAttribute('aria-disabled') !== 'true');
for (const [aria, re] of arg) {
  const rx = new RegExp(re, 'i');
  const b = btns.find((x) => (x.getAttribute('aria-label') || '') === aria)
    || btns.find((x) => rx.test(x.getAttribute('aria-label') || '') || rx.test(clean(x.textContent)));
  if (b) { b.setAttribute('data-aa-btn', '1'); return true; }
}
return false;

//# tag_option
// arg: selector of typeahead suggestions. Tags the first visible one.
document.querySelectorAll('[data-aa-opt]').forEach((e) => e.removeAttribute('data-aa-opt'));
const o = [...document.querySelectorAll(arg)].slice(0, 6).find(vis);
if (!o) return false;
o.setAttribute('data-aa-opt', '1');
return true;

//# key
// arg: {sel, key}. Presses a key (ArrowDown, Enter, Escape) on an element or on the focused one.
const el = (arg.sel && document.querySelector(arg.sel)) || document.activeElement || document.body;
const o = { key: arg.key, code: arg.key, bubbles: true, cancelable: true };
el.dispatchEvent(new KeyboardEvent('keydown', o));
el.dispatchEvent(new KeyboardEvent('keyup', o));
return true;

//# tag_dismiss
// arg: selector of close buttons. Tags the close (X) button of the form.
document.querySelectorAll('[data-aa-x]').forEach((e) => e.removeAttribute('data-aa-x'));
const m = modalEl();
const x = m ? m.querySelector(arg) : null;
if (!x) return false;
x.setAttribute('data-aa-x', '1');
return true;

//# tag_discard
// arg: selector of LinkedIn's discard button. Tags "Discard" in the "Save this application?" dialog.
document.querySelectorAll('[data-aa-discard]').forEach((e) => e.removeAttribute('data-aa-discard'));
const b = [...document.querySelectorAll(arg)].find(vis)
  || [...document.querySelectorAll('button')].find((x) => vis(x) && /^\s*discard\s*$/i.test(x.textContent || ''));
if (!b) return false;
b.setAttribute('data-aa-discard', '1');
return true;

//# html
return document.documentElement.outerHTML;

//# lang
return document.documentElement.lang || '';

//# ready
return document.readyState;
