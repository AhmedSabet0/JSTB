/* Auto Apply: fills a job application form on a company's site that you opened in the app. You check it and submit
   it yourself. collect() lists the form's empty fields; apply(values) fills them and marks them in green. */
(function () {
  'use strict';
  if (window.AAFill) return;
  var SKIP = /^(hidden|submit|button|reset|image|file|password|search)$/i;

  function clean(s) {
    return String(s || '').replace(/\s+/g, ' ').replace(/\s*[*:]+\s*$/, '').replace(/^\s*[*]\s*/, '').trim();
  }
  function visible(el) {
    var st = window.getComputedStyle(el);
    var r = el.getBoundingClientRect();
    return st.visibility !== 'hidden' && st.display !== 'none' && (r.width > 0 || r.height > 0);
  }
  function textOf(el) {
    return el ? clean(el.innerText || el.textContent || '') : '';
  }
  function labelOf(el) {
    if (el.id) {
      var byFor = document.querySelector('label[for="' + (window.CSS && CSS.escape ? CSS.escape(el.id) : el.id) + '"]');
      if (textOf(byFor)) return textOf(byFor);
    }
    var aria = el.getAttribute('aria-label');
    if (clean(aria)) return clean(aria);
    var by = el.getAttribute('aria-labelledby');
    if (by) {
      var t = by.split(/\s+/).map(function (id) { return textOf(document.getElementById(id)); }).join(' ');
      if (clean(t)) return clean(t);
    }
    var wrap = el.closest('label');
    if (wrap && textOf(wrap)) return textOf(wrap);
    var box = el.parentElement;
    for (var i = 0; i < 4 && box; i++, box = box.parentElement) {
      var lab = box.querySelector('label, legend, [class*="label"], [class*="Label"], [class*="question"]');
      if (lab && !lab.contains(el) && textOf(lab)) return textOf(lab);
    }
    return clean(el.getAttribute('placeholder') || el.getAttribute('name') || '');
  }
  function groupLabel(el) {
    var fs = el.closest('fieldset');
    if (fs && textOf(fs.querySelector('legend'))) return textOf(fs.querySelector('legend'));
    var box = el.closest('[role="radiogroup"], [role="group"]');
    if (box) {
      var by = box.getAttribute('aria-labelledby');
      if (by && textOf(document.getElementById(by))) return textOf(document.getElementById(by));
      if (clean(box.getAttribute('aria-label'))) return clean(box.getAttribute('aria-label'));
    }
    var p = el.parentElement;
    for (var i = 0; i < 5 && p; i++, p = p.parentElement) {
      var lab = p.querySelector('legend, [class*="label"], [class*="question"], p, span');
      if (lab && !lab.contains(el) && !lab.querySelector('input') && textOf(lab)) return textOf(lab);
    }
    return clean(el.name || '');
  }
  function optionLabel(input) {
    return labelOf(input) || clean(input.value);
  }
  function isRequired(el, label) {
    return !!(el.required || el.getAttribute('aria-required') === 'true' || /\*\s*$/.test(String(label)));
  }

  var fields = [];
  function collect() {
    fields = [];
    var out = [];
    var groups = {};
    var els = document.querySelectorAll('input, select, textarea');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var type = (el.getAttribute('type') || el.tagName).toLowerCase();
      if (el.disabled || el.readOnly || SKIP.test(type) || !visible(el)) continue;
      if (type === 'radio' || type === 'checkbox') {
        var name = el.name || el.id || ('aa' + i);
        var g = groups[type + ':' + name];
        if (!g) {
          g = groups[type + ':' + name] = { type: type, els: [], name: name };
        }
        g.els.push(el);
        continue;
      }
      var rawLabel = (el.id && document.querySelector('label[for="' + el.id + '"]')) ? document.querySelector('label[for="' + el.id + '"]').innerText : '';
      var f = {
        key: 'f' + fields.length,
        label: labelOf(el),
        kind: el.tagName === 'SELECT' ? 'select' : el.tagName === 'TEXTAREA' ? 'textarea'
          : type === 'email' ? 'email' : type === 'tel' ? 'tel' : type === 'number' ? 'number' : type === 'url' ? 'url'
            : type === 'date' ? 'date' : 'text',
        options: el.tagName === 'SELECT' ? Array.prototype.map.call(el.options, function (o) { return clean(o.text); })
          .filter(function (t) { return t && !/^(select|choose|please select|--)/i.test(t); }) : [],
        value: el.tagName === 'SELECT' ? (el.selectedIndex > 0 ? clean(el.options[el.selectedIndex].text) : '') : String(el.value || ''),
        placeholder: clean(el.getAttribute('placeholder') || ''),
        maxlength: el.maxLength > 0 ? el.maxLength : 0,
        required: isRequired(el, rawLabel || labelOf(el)),
      };
      fields.push({ f: f, els: [el] });
      out.push(f);
    }
    Object.keys(groups).forEach(function (k) {
      var g = groups[k];
      var opts = g.els.map(optionLabel);
      var label = g.type === 'checkbox' && g.els.length === 1 ? labelOf(g.els[0]) : groupLabel(g.els[0]);
      var f = {
        key: 'f' + fields.length,
        label: label,
        kind: g.type === 'radio' ? 'radio' : (g.els.length === 1 ? 'checkbox' : 'multi'),
        options: g.type === 'checkbox' && g.els.length === 1 ? [] : opts,
        value: g.els.filter(function (e) { return e.checked; }).map(optionLabel).join(', '),
        placeholder: '',
        maxlength: 0,
        required: isRequired(g.els[0], label),
      };
      fields.push({ f: f, els: g.els });
      out.push(f);
    });
    return JSON.stringify(out);
  }

  function setValue(el, v) {
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, v); else el.value = v;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new Event('blur', { bubbles: true }));
  }
  function same(a, b) {
    a = clean(a).toLowerCase();
    b = clean(b).toLowerCase();
    return a === b || (a && b && (a.indexOf(b) === 0 || b.indexOf(a) === 0));
  }
  function mark(el) {
    el.style.outline = '2px solid #11805b';
    el.style.outlineOffset = '1px';
  }

  function apply(values) {
    var n = 0;
    fields.forEach(function (item) {
      var v = values[item.f.key];
      if (v === undefined || v === null) return;
      var f = item.f;
      var el = item.els[0];
      if (f.kind === 'select') {
        var want = Array.isArray(v) ? v[0] : v;
        for (var i = 0; i < el.options.length; i++) {
          if (same(el.options[i].text, want) || same(el.options[i].value, want)) {
            el.selectedIndex = i;
            el.dispatchEvent(new Event('change', { bubbles: true }));
            mark(el);
            n++;
            return;
          }
        }
      } else if (f.kind === 'radio' || f.kind === 'multi') {
        var wants = Array.isArray(v) ? v : [v];
        var hit = false;
        item.els.forEach(function (r) {
          if (wants.some(function (w) { return same(optionLabel(r), w); }) && !r.checked) {
            r.click();
            hit = true;
          }
        });
        if (hit) { mark(item.els[0].closest('fieldset') || item.els[0].parentElement); n++; }
      } else if (f.kind === 'checkbox') {
        if (v && !el.checked) { el.click(); mark(el); n++; }
      } else {
        setValue(el, Array.isArray(v) ? v.join(', ') : String(v));
        mark(el);
        n++;
      }
    });
    return n;
  }

  // Application forms that live in a frame from another site (Greenhouse, Lever...): the app opens the frame itself.
  var ATS = /greenhouse\.io|lever\.co|workable\.com|smartrecruiters\.com|ashbyhq\.com|bamboohr\.com|jobvite\.com|icims\.com|myworkdayjobs\.com|recruitee\.com|breezy\.hr|teamtailor\.com|personio\.|zohorecruit|jazzhr|applytojob\.com/i;
  function frames() {
    var out = [];
    var list = document.querySelectorAll('iframe[src]');
    for (var i = 0; i < list.length; i++) {
      var src = list[i].src || '';
      if (/^https:\/\//i.test(src) && ATS.test(src) && visible(list[i])) out.push(src);
    }
    return JSON.stringify(out);
  }

  // The page as text, for the job's fit with your CV, and whether it asks for a file (your CV).
  function page() {
    var body = document.body ? (document.body.innerText || '') : '';
    return JSON.stringify({
      title: clean(document.title || ''),
      heading: textOf(document.querySelector('h1')),
      text: body.replace(/\n{3,}/g, '\n\n').slice(0, 9000),
      file: !!document.querySelector('input[type="file"]'),
      url: location.href,
    });
  }

  window.AAFill = { collect: collect, apply: apply, frames: frames, page: page };
}());
