/* JSTB extension: reads and fills the fields of an application form. Used by every site driver. */
(function () {
  'use strict';
  if (window.JSTBForm) return;

  const GROUP = '[data-test-form-element], .fb-dash-form-element, .jobs-easy-apply-form-element, '
    + '.jobs-easy-apply-form-section__grouping, .form-group, .field, [class*="form-field"], [class*="FormField"], '
    + '[class*="question"], [role="group"], [role="radiogroup"]';
  const ERR_SEL = '.artdeco-inline-feedback--error, [data-test-form-element-error-messages], [role="alert"], '
    + '.error, .invalid-feedback, [class*="error-message"], [class*="errorMessage"], [class*="field-error"], [class*="error" i], [id$="-error"], [data-test*="error" i]';
  const SKIP_TYPES = ['hidden', 'file', 'submit', 'button', 'search', 'image', 'reset', 'password'];
  const SKIP_NAME = /follow-company|top-choice|top choice|captcha|honeypot|search/i;
  let counter = 0;

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  function vis(el) {
    if (!el || !el.isConnected) return false;
    const st = getComputedStyle(el);
    if (st.display === 'none' || st.visibility === 'hidden') return false;
    return el.getClientRects().length > 0;
  }

  function clean(s) {
    let t = String(s || '').replace(/\s+/g, ' ').trim();
    const n = t.length;
    if (n >= 4 && n % 2 === 0 && t.slice(0, n / 2) === t.slice(n / 2)) t = t.slice(0, n / 2);
    else if (n >= 5 && n % 2 === 1 && t[(n - 1) / 2] === ' ' && t.slice(0, (n - 1) / 2) === t.slice((n + 1) / 2)) {
      t = t.slice(0, (n - 1) / 2);
    }
    return t.replace(/\s*[*:]+\s*$/, '').replace(/\s*\bRequired\s*$/i, '').replace(/^\s*\*\s*/, '').trim();
  }

  function txt(node) {
    if (!node) return '';
    const hidden = node.querySelector ? node.querySelector('span[aria-hidden="true"]') : null;
    const a = hidden ? clean(hidden.textContent) : '';
    return a.length >= 2 ? a : clean(node.innerText || node.textContent);
  }

  function labelFor(el) {
    return el && el.id ? document.querySelector('label[for="' + CSS.escape(el.id) + '"]') : null;
  }

  function groupOf(el) {
    return el.closest('fieldset') || el.closest(GROUP) || el.parentElement;
  }

  function questionFor(el) {
    const tries = [];
    if (el.tagName === 'FIELDSET' || el.getAttribute('role') === 'radiogroup' || el.getAttribute('role') === 'group') {
      tries.push(txt(el.querySelector('legend')));
      const by = el.getAttribute('aria-labelledby');
      if (by) tries.push(by.split(/\s+/).map((id) => txt(document.getElementById(id))).join(' '));
      tries.push(clean(el.getAttribute('aria-label')));
    } else {
      tries.push(txt(labelFor(el)));
      tries.push(clean(el.getAttribute('aria-label')));
      const by = el.getAttribute('aria-labelledby');
      if (by) tries.push(by.split(/\s+/).map((id) => txt(document.getElementById(id))).join(' '));
      const wrap = el.closest('label');
      if (wrap) tries.push(txt(wrap));
    }
    for (const t of tries) if (t && t.length >= 2) return t.slice(0, 400);
    let box = el.parentElement;
    for (let i = 0; i < 4 && box; i++, box = box.parentElement) {
      const lab = box.querySelector('label, legend, [class*="label"], [class*="Label"], [class*="question"], [class*="title"]');
      if (lab && !lab.contains(el) && txt(lab)) return txt(lab).slice(0, 400);
    }
    return clean(el.getAttribute('placeholder') || el.getAttribute('name') || '').replace(/[_\-[\]]+/g, ' ').trim();
  }

  function optionLabel(i) {
    const l = labelFor(i) || i.closest('label');
    return txt(l) || clean(i.getAttribute('aria-label')) || clean(i.value);
  }

  function fieldError(el) {
    const ids = (el.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean);
    for (const id of ids) {
      const d = document.getElementById(id);
      if (d && vis(d) && /error|invalid|alert/i.test(d.className + ' ' + (d.getAttribute('role') || '') + ' ' + id)) {
        return clean(d.textContent).slice(0, 200);
      }
    }
    const g = groupOf(el);
    const e = g ? [...g.querySelectorAll(ERR_SEL)].find((x) => vis(x) && clean(x.textContent) && clean(x.textContent).length < 160
      && !x.querySelector('input, select, textarea')) : null;
    if (e) return clean(e.textContent).slice(0, 200);
    return el.getAttribute('aria-invalid') === 'true' ? 'Invalid input' : '';
  }

  function tag(el) {
    counter += 1;
    const id = 'j' + counter;
    el.setAttribute('data-jstb', id);
    return id;
  }

  /** The fields of the form inside `root`: [{kind, id, question, value|selected|options, error, required, maxlen}]. */
  function scan(root) {
    const out = [];
    const seen = new Set();
    if (!root) return out;
    for (const el of root.querySelectorAll('input, textarea, select')) {
      const type = (el.getAttribute('type') || '').toLowerCase();
      if (SKIP_TYPES.includes(type)) continue;
      if (SKIP_NAME.test(el.id || '') || SKIP_NAME.test(el.getAttribute('name') || '')) continue;
      if (type === 'radio' || type === 'checkbox') {
        const group = el.closest('fieldset, [role="radiogroup"], [role="group"]') || groupOf(el);
        if (!group || seen.has(group)) continue;
        seen.add(group);
        if (!vis(group)) continue;
        const inputs = [...group.querySelectorAll('input[type="radio"], input[type="checkbox"]')]
          .filter((i) => !SKIP_NAME.test(i.id || '') && !SKIP_NAME.test(optionLabel(i)));
        if (!inputs.length) continue;
        const options = inputs.map((i) => ({ id: tag(i), label: optionLabel(i), checked: !!i.checked }));
        let kind = inputs[0].type === 'radio' ? 'radio' : (inputs.length === 1 ? 'single_checkbox' : 'checkbox');
        if (options.every((o) => /\.(pdf|docx?)\b/i.test(o.label))) kind = 'resume';
        const required = inputs.some((i) => i.required || i.getAttribute('aria-required') === 'true')
          || /\*|required/i.test(group.textContent || '');
        let question = questionFor(group);
        if (kind === 'single_checkbox' && (!question || question === options[0].label)) question = options[0].label;
        out.push({ kind, question, options, error: fieldError(group), required, fieldset: group.tagName === 'FIELDSET' });
        continue;
      }
      if (!vis(el) || el.disabled || el.readOnly) continue;
      const id = tag(el);
      const required = el.required || el.getAttribute('aria-required') === 'true';
      if (el.tagName === 'SELECT') {
        const isPh = (o) => !o || o.value === '' || /^(select( an)? option|select|choose|please select|--)/i.test(o.textContent.trim());
        const cur = el.options[el.selectedIndex];
        out.push({
          kind: 'select', id, question: questionFor(el), error: fieldError(el), required,
          selected: isPh(cur) ? '' : clean(cur.textContent),
          options: [...el.options].map((o, index) => ({ label: clean(o.textContent), index, ph: isPh(o) }))
            .filter((o) => !o.ph && o.label),
        });
        continue;
      }
      let kind = 'text';
      if (el.tagName === 'TEXTAREA') kind = 'textarea';
      else if (type === 'number' || /numeric/i.test(el.id || '') || /numeric|decimal/i.test(el.getAttribute('inputmode') || '')) kind = 'numeric';
      else if (type === 'email') kind = 'email';
      else if (type === 'tel') kind = 'tel';
      else if (type === 'date') kind = 'date';
      else if (type === 'url') kind = 'url';
      const typeahead = el.getAttribute('role') === 'combobox' || !!el.getAttribute('aria-autocomplete');
      out.push({
        kind, id, question: questionFor(el), value: el.value || '', error: fieldError(el), required, typeahead,
        placeholder: el.getAttribute('placeholder') || '', maxlen: el.maxLength > 0 ? el.maxLength : 0,
        auto: el.getAttribute('autocomplete') || '', name: el.getAttribute('name') || '',
      });
    }
    return out;
  }

  const byId = (id) => document.querySelector('[data-jstb="' + id + '"]');

  function type(id, value, typeahead) {
    const el = byId(id);
    if (!el) return false;
    try { el.scrollIntoView({ block: 'center' }); } catch (e) { /* not scrollable */ }
    el.focus();
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, '');
    el.dispatchEvent(new Event('input', { bubbles: true }));
    const v = String(value);
    const steps = typeahead ? [...v].map((_, i) => [...v].slice(0, i + 1).join('')) : [v];
    for (const part of steps) {
      const ch = part.slice(-1);
      el.dispatchEvent(new KeyboardEvent('keydown', { key: ch, bubbles: true }));
      setter.call(el, part);
      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: ch, inputType: 'insertText' }));
      el.dispatchEvent(new KeyboardEvent('keyup', { key: ch, bubbles: true }));
    }
    el.dispatchEvent(new Event('change', { bubbles: true }));
    if (!typeahead) el.blur();
    return true;
  }

  async function pickSuggestion(id) {
    const el = byId(id);
    for (let i = 0; i < 12; i++) {
      await sleep(250);
      const opt = [...document.querySelectorAll('[role="listbox"] [role="option"], .basic-typeahead__selectable, '
        + '.search-typeahead-v2__hit, .autocomplete-suggestion')].find(vis);
      if (opt) {
        opt.click();
        return true;
      }
    }
    if (el) el.blur();
    return false;
  }

  function select(id, index) {
    const el = byId(id);
    if (!el || !el.options[index]) return false;
    el.focus();
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(el, el.options[index].value);
    el.selectedIndex = index;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.blur();
    return el.selectedIndex === index;
  }

  function check(id, want) {
    const inp = byId(id);
    if (!inp) return false;
    if (inp.checked === (want !== false)) return true;
    try { inp.scrollIntoView({ block: 'center' }); } catch (e) { /* not scrollable */ }
    const lab = labelFor(inp) || inp.closest('label');
    if (lab && vis(lab)) lab.click();
    if (inp.checked !== (want !== false)) inp.click();
    return inp.checked === (want !== false);
  }

  function upload(input, cv) {
    const bin = atob(cv.b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const dt = new DataTransfer();
    dt.items.add(new File([bytes], cv.name, { type: cv.mime, lastModified: Date.now() }));
    input.files = dt.files;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    return input.files.length > 0;
  }

  function norm(s) {
    return String(s || '').toLowerCase().replace(/\s+/g, ' ').trim();
  }

  function matchOption(labels, answer) {
    const a = norm(answer);
    if (!a) return -1;
    let i = labels.findIndex((l) => norm(l) === a);
    if (i < 0) i = labels.findIndex((l) => norm(l).startsWith(a) || a.startsWith(norm(l)));
    if (i < 0) i = labels.findIndex((l) => norm(l).includes(a) || a.includes(norm(l)));
    return i;
  }

  /** What to ask the background for: the fields that still need an answer. `retry` = also the ones with an error. */
  function wanted(fields, retry) {
    const ask = [];
    for (const f of fields) {
      const redo = retry && !!f.error;
      if (!f.question) continue;
      if (['text', 'numeric', 'textarea', 'email', 'tel', 'url', 'date'].includes(f.kind)) {
        if (f.value.trim() && !redo) continue;
        if (!f.required && !redo && f.kind === 'textarea' && /cover letter|additional|anything else|comments?|message/i.test(f.question)
            && !/cover letter/i.test(f.question)) continue;
        ask.push({ key: f.id, question: f.question, kind: f.kind === 'numeric' ? 'numeric' : (f.kind === 'textarea' ? 'textarea' : 'text'),
          input: f.kind, maxlen: f.maxlen, placeholder: f.placeholder, error: redo ? f.error : '', rejected: redo ? f.value : '',
          auto: f.auto, name: f.name, required: f.required });
      } else if (f.kind === 'select') {
        if (f.selected && !redo) continue;
        if (!f.options.length) continue;
        ask.push({ key: f.id, question: f.question, kind: 'choice', options: f.options.map((o) => o.label),
          error: redo ? f.error : '', rejected: redo ? f.selected : '', required: f.required });
      } else if (f.kind === 'radio') {
        if (f.options.some((o) => o.checked) && !redo) continue;
        ask.push({ key: f.options[0].id, question: f.question, kind: 'choice', options: f.options.map((o) => o.label),
          error: redo ? f.error : '', required: true });
      } else if (f.kind === 'checkbox') {
        if (f.options.some((o) => o.checked) && !redo) continue;
        if (!f.required && !f.fieldset && !redo) continue;
        ask.push({ key: f.options[0].id, question: f.question, kind: 'multi', options: f.options.map((o) => o.label),
          error: redo ? f.error : '', required: f.required });
      }
    }
    return ask;
  }

  /** Put the answers ({key: value}) into the fields. Returns [{q, a}] of what was filled. */
  async function apply(fields, answers) {
    const done = [];
    for (const f of fields) {
      const key = f.id || (f.options && f.options[0] && f.options[0].id);
      if (f.kind === 'single_checkbox') {   // "I agree", "I confirm": tick what is required
        const must = f.required || !!f.error || /\b(agree|consent|confirm|acknowledge|certify|privacy|terms)\b/i.test(f.question);
        if (must && !f.options[0].checked && !/follow|subscribe|newsletter|marketing|job alert|promotion/i.test(f.question)) {
          check(f.options[0].id, true);
        }
        continue;
      }
      if (f.kind === 'resume') {
        if (!f.options.some((o) => o.checked)) check(f.options[0].id, true);
        continue;
      }
      const a = answers[key];
      if (a === undefined || a === null || a === '') continue;
      if (['text', 'numeric', 'textarea', 'email', 'tel', 'url', 'date'].includes(f.kind)) {
        let v = String(a);
        if (f.maxlen > 0 && v.length > f.maxlen) v = v.slice(0, f.maxlen);
        if (type(f.id, v, f.typeahead)) {
          if (f.typeahead) await pickSuggestion(f.id);
          done.push({ q: f.question, a: v });
        }
      } else if (f.kind === 'select') {
        const i = matchOption(f.options.map((o) => o.label), a);
        if (i >= 0 && select(f.id, f.options[i].index)) done.push({ q: f.question, a: f.options[i].label });
      } else if (f.kind === 'radio') {
        const i = matchOption(f.options.map((o) => o.label), a);
        if (i >= 0 && check(f.options[i].id, true)) done.push({ q: f.question, a: f.options[i].label });
      } else if (f.kind === 'checkbox') {
        const list = Array.isArray(a) ? a : String(a).split(/\s*[;|]\s*/);
        const picked = [];
        for (const one of list) {
          const i = matchOption(f.options.map((o) => o.label), one);
          if (i >= 0 && check(f.options[i].id, true)) picked.push(f.options[i].label);
        }
        if (picked.length) done.push({ q: f.question, a: picked.join('; ') });
      }
      await sleep(120);
    }
    return done;
  }

  function send(msg) {
    return new Promise((resolve) => {
      try {
        chrome.runtime.sendMessage(msg, (r) => resolve(chrome.runtime.lastError ? null : r));
      } catch (e) {
        resolve(null);
      }
    });
  }

  /** Read the form in `root`, ask for the answers, fill them. `retry` repairs rejected fields. Returns what it filled. */
  async function fill(root, ctx, retry) {
    let filled = [];
    for (let pass = 0; pass < 2; pass++) {   // a second pass catches questions an earlier answer revealed
      const fields = scan(root());
      const ask = wanted(fields, retry && pass === 0);
      let answers = {};
      if (ask.length) {
        const r = await send({ t: 'answers', fields: ask, ctx });
        answers = (r && r.answers) || {};
      }
      const done = await apply(fields, answers);
      filled = filled.concat(done);
      if (!done.length) break;
      await sleep(400);
    }
    return filled;
  }

  async function uploadCv(root, force) {
    const r = root();
    if (!r) return false;
    const inp = [...r.querySelectorAll('input[type="file"]')]
      .find((i) => !/cover|letter|photo|image|avatar|picture/i.test(`${i.id} ${i.name} ${i.getAttribute('aria-label') || ''} ${i.accept || ''}`));
    if (!inp || (inp.files && inp.files.length && !force)) return false;
    const cv = await send({ t: 'cv' });
    if (!cv || !cv.b64) return false;
    return upload(inp, cv);
  }

  async function waitFor(fn, seconds, every) {
    const end = Date.now() + seconds * 1000;
    while (Date.now() < end) {
      const v = fn();
      if (v) return v;
      await sleep(every || 300);
    }
    return null;
  }

  function click(el) {
    if (!el) return false;
    try { el.scrollIntoView({ block: 'center' }); } catch (e) { /* not scrollable */ }
    el.click();
    return true;
  }

  function buttons(root, re, not) {
    return [...root.querySelectorAll('button, [role="button"], input[type="submit"], a.btn, a[class*="button"], a[class*="Button"]')]
      .filter((b) => vis(b) && !b.disabled && b.getAttribute('aria-disabled') !== 'true')
      .filter((b) => {
        const t = clean((b.getAttribute('aria-label') || '') + ' ' + (b.innerText || b.value || b.textContent || ''));
        return re.test(t) && !(not && not.test(t));
      });
  }

  window.JSTBForm = { sleep, vis, clean, txt, scan, fill, uploadCv, waitFor, click, buttons, send, fieldError, norm };
})();
