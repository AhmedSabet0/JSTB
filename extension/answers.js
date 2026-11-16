/* JSTB extension: the answer to each form question. First what you already answered, then your JSTB setup answers
   (phone, city, salary, notice period...), then the AI of your JSTB account, with your CV. */

const SYSTEM = 'You fill job application forms for the candidate. Answer truthfully using only the CV, the candidate notes '
  + 'and the known answers. Never invent degrees, certifications, employers or skills. For voluntary demographic questions '
  + "(gender, race, ethnicity, veteran, disability, sexual orientation) pick the 'prefer not to say' or 'decline' option "
  + 'when one exists. For salary or compensation questions always reply with a clean number in digits only (e.g. 20000), '
  + "never words like 'Negotiable'. For years of experience with X, reply with the total years since the first job in the "
  + 'CV if X is in the CV, or 1 if not. Any "how many" or "how much" question gets a number in digits only. Reply with JSON only.';

const norm = (s) => String(s || '').toLowerCase().replace(/[’]/g, "'").replace(/\s+/g, ' ').replace(/[\s*:?]+$/, '').trim();
const has = (q, re) => re.test(q);

function option(options, wanted) {
  const w = norm(wanted);
  if (!w) return null;
  return options.find((o) => norm(o) === w) || options.find((o) => norm(o).startsWith(w) || w.startsWith(norm(o)))
    || options.find((o) => norm(o).includes(w)) || null;
}
function yesNo(options, yes) {
  if (!options.length) return yes ? 'Yes' : 'No';
  return options.find((o) => (yes ? /^(yes|true)\b/i : /^(no|false)\b/i).test(o.trim())) || null;
}
function flag(v) {
  const t = norm(v);
  if (['yes', 'true', 'y'].includes(t)) return true;
  if (['no', 'false', 'n'].includes(t)) return false;
  return null;
}
const digits = (v) => { const m = String(v || '').replace(/,/g, '').match(/\d+(?:\.\d+)?/); return m ? m[0].replace(/\.0+$/, '') : ''; };

function nameFrom(user, cvText) {
  if (user && user.name && /\S+\s+\S+/.test(user.name)) return user.name.trim();
  const line = String(cvText || '').split(/\n/).map((l) => l.trim()).find((l) => /^[A-Za-z][A-Za-z.'-]+(\s+[A-Za-z][A-Za-z.'-]+){1,3}$/.test(l));
  return line || (user && user.name) || '';
}

/** An answer from your setup answers, or null when they do not cover this question. */
export function ruleAnswer(f, data, user) {
  const q = norm(f.question);
  const p = data.profile || {};
  const opts = f.options || [];
  const choice = f.kind === 'choice';
  if (f.kind === 'multi' || f.kind === 'textarea') return null;
  const text = (v) => (choice ? option(opts, v) : (v || null));
  const auto = norm(f.auto);
  const name = nameFrom(user, data.cvText);
  const parts = name.split(/\s+/);

  if (f.input === 'email' || auto === 'email' || has(q, /^(e-?mail|email address|your email)/)) return text(user && user.email);
  if (auto === 'given-name' || has(q, /^(first|given) name/)) return text(parts[0]);
  if (auto === 'family-name' || has(q, /^(last|family) name|^surname/)) return text(parts.slice(1).join(' '));
  if (auto === 'name' || has(q, /^(full |your )?name$|^full legal name/)) return text(name);
  if (f.input === 'tel' || auto.startsWith('tel') || has(q, /\b(phone|mobile|telephone|whatsapp)\b/)) {
    return choice ? null : (p.phone || null);
  }
  if (has(q, /\b(sponsor|sponsorship)\b/)) { const b = flag(p.sponsorship); return b === null ? null : yesNo(opts, b); }
  if (has(q, /(legally )?(authori[sz]ed|eligible|permitted|right) to work|work (permit|authori[sz]ation)/)) {
    if (!p.authorized) return null;
    const b = flag(p.authorized);
    return yesNo(opts, b === null ? true : b);
  }
  if (has(q, /\b(relocat\w+)\b/)) { const b = flag(p.relocate); return b === null ? null : yesNo(opts, b); }
  if (has(q, /\b(on-?site|commut\w+|work from (the )?office|in[- ]office|hybrid)\b/) && (choice ? !!yesNo(opts, true) : /^(are|can|will|do|would)\b/.test(q))) {
    const b = flag(p.onsite);
    return b === null ? null : yesNo(opts, b);
  }
  if (has(q, /notice period|how soon can you (start|join)|when can you (start|join)|availability to (start|join)/)) {
    if (p.notice_days === undefined || p.notice_days === '') return null;
    if (f.kind === 'numeric') return /week/.test(q) ? String(Math.ceil(Number(p.notice_days) / 7)) : (/month/.test(q) ? String(Math.ceil(Number(p.notice_days) / 30)) : String(p.notice_days));
    const days = Number(p.notice_days);
    if (choice) {
      if (days === 0) return opts.find((o) => /immediate|right away|asap|\bnow\b|^0\b|none/i.test(o)) || null;
      const inDays = (o) => {       // "2 weeks", "1 month", "30 days" -> days
        const m = o.match(/(\d+)\s*(day|week|month)/i);
        if (!m) return /immediate/i.test(o) ? 0 : null;
        return Number(m[1]) * ({ day: 1, week: 7, month: 30 }[m[2].toLowerCase()]);
      };
      const fits = opts.map((o) => ({ o, d: inDays(o) })).filter((x) => x.d !== null).sort((x, y) => Math.abs(x.d - days) - Math.abs(y.d - days));
      return fits.length ? fits[0].o : null;
    }
    return days === 0 ? 'Immediately' : p.notice_days + ' days';
  }
  if (has(q, /\b(salary|compensation|remuneration|pay)\b/) && !choice) {
    const current = has(q, /\b(current|present|last)\b/);
    const v = digits(current ? p.current_salary : p.expected_salary);
    return v || null;
  }
  if (has(q, /currency/)) return text(p.currency);
  if (has(q, /military/)) return text(p.military);
  if (has(q, /driv(ing|er'?s?) licen[cs]e/)) { const b = flag(p.driving); return b === null ? null : yesNo(opts, b); }
  if (has(q, /english (level|proficiency)|proficiency in english|level of english/)) return text(p.english);
  if (has(q, /graduat(ion|ed)? (year|date)|year of graduation/)) return text(p.graduation_year);
  if (!choice && has(q, /^(city|current (city|location)|location|where (do you|are you) (live|located|based))/)) return p.city || null;
  if (auto === 'address-level2') return text(p.city);
  if (has(q, /linkedin/) && !choice) {
    const m = String(data.cvText || '').match(/(https?:\/\/)?(www\.)?linkedin\.com\/in\/[\w\-%.]+/i);
    return m ? (m[1] ? m[0] : 'https://' + m[0]) : null;
  }
  if (has(q, /github/) && !choice) {
    const m = String(data.cvText || '').match(/(https?:\/\/)?(www\.)?github\.com\/[\w\-.]+/i);
    return m ? (m[1] ? m[0] : 'https://' + m[0]) : null;
  }
  return null;
}

/** The form refused the phone number: the same number written another way (without the country code, digits only). */
function phoneAgain(f, data) {
  const phone = String((data.profile || {}).phone || '').replace(/[\s\-().]/g, '');
  if (!phone || f.kind === 'choice' || !(f.input === 'tel' || /\b(phone|mobile|telephone)\b/i.test(f.question))) return null;
  const plain = phone.replace(/^\+/, '').replace(/^00/, '');
  const forms = [phone, plain.replace(/^20(?=1\d{9}$)/, '0'), plain, plain.replace(/^0+/, '')];
  const tried = String(f.rejected || '').replace(/[\s\-().]/g, '');
  const i = forms.indexOf(tried);
  const next = forms.slice(i + 1).find((x) => x && x !== tried);
  return next || null;
}

function notes(data, user) {
  const p = data.profile || {};
  const l = [];
  const add = (label, v) => { if (v) l.push(label + ': ' + v); };
  add('Name', nameFrom(user, data.cvText));
  add('Email', user && user.email);
  add('Phone', p.phone);
  add('Lives in', p.city);
  add('Graduated in', p.graduation_year);
  if (p.current_salary) l.push('Current monthly salary: ' + p.current_salary + ' ' + (p.currency || ''));
  if (p.expected_salary) l.push('Expected monthly salary: ' + p.expected_salary + ' ' + (p.currency || ''));
  if (p.notice_days !== undefined && p.notice_days !== '') l.push(p.notice_days === '0' ? 'Notice period: none, can start immediately' : 'Notice period: ' + p.notice_days + ' days');
  add('Military service', p.military);
  add('Willing to relocate', p.relocate);
  add('OK with on-site work and commuting', p.onsite);
  add('Authorized to work in', p.authorized);
  add('Needs visa sponsorship', p.sponsorship);
  add('English level', p.english);
  add('Driving license', p.driving);
  return l.join('\n');
}

function fit(f, a) {
  if (a === null || a === undefined || a === '') return null;
  const opts = f.options || [];
  if (f.kind === 'choice') return option(opts, Array.isArray(a) ? a[0] : a);
  if (f.kind === 'multi') {
    const list = (Array.isArray(a) ? a : String(a).split(/\s*[|;]\s*/)).map((x) => option(opts, x)).filter(Boolean);
    return list.length ? list : null;
  }
  let v = String(Array.isArray(a) ? a.join(', ') : a).trim();
  if (f.kind === 'numeric') {
    if (/\b(less than|under)\s*(1|one)\b/i.test(v)) return '0';
    v = digits(v);
    return v || null;
  }
  if (f.input === 'date') return /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : null;
  if (f.maxlen > 0 && v.length > f.maxlen) v = v.slice(0, f.maxlen);
  return v || null;
}

const STOP = new Set(('a an and are as at be by do for from has have how in is it many much of on or other such the this to using '
  + 'what with work working you your years year experience currently total professional relevant related hands').split(' '));

/** The year of the first job in the CV: the earliest year in its experience section. */
export function guessStartYear(cvText) {
  const t = String(cvText || '');
  const at = t.search(/\b(work|professional|employment)?\s*(experience|history)\b|\bemployment\b/i);
  let sec = at >= 0 ? t.slice(at) : t;
  const end = sec.slice(25).search(/\n\s*(education|certifications?|certificates|skills|projects|courses|training|languages)\b/i);
  if (at >= 0 && end >= 0) sec = sec.slice(0, end + 25);
  const thisYear = new Date().getFullYear();
  const years = (sec.match(/\b(19[89]\d|20\d\d)\b/g) || []).map(Number).filter((y) => y <= thisYear);
  return years.length ? Math.min(...years) : 0;
}

/** "How many years of experience with X?": the years since your first job when X is in your CV, otherwise your default. */
export function experienceYears(f, data) {
  if (f.kind === 'choice' || f.kind === 'multi' || f.kind === 'textarea') return null;
  const q = norm(f.question);
  if (!/\byears?\b/.test(q) || !/experience|worked|working|\busing\b|\bused\b|how long/.test(q)) return null;
  const p = data.profile || {};
  const start = parseInt(p.work_start_year, 10) || guessStartYear(data.cvText);
  if (!start) return null;
  const total = Math.max(1, new Date().getFullYear() - start);
  const other = String(parseInt(p.default_years, 10) >= 0 ? parseInt(p.default_years, 10) : 1);
  const m = q.match(/(?:experience|worked|working|expertise)\s+(?:do you (?:currently |already )?have\s+)?(?:in|with|using|as an?|on|of)\s+(.+)$/)
    || q.match(/years of (.+?) experience/) || q.match(/how long have you (?:been )?(?:worked|working|used|using)\s+(?:with|in|on|as an?)?\s*(.+)$/);
  if (!m) return String(total);                       // "years of experience" in general
  const cv = ' ' + String(data.cvText || '').toLowerCase() + ' ';
  const words = m[1].replace(/[^a-z0-9+#.\s/-]/g, ' ').split(/[\s/,]+/).map((w) => w.replace(/^[.-]+|[.-]+$/g, ''))
    .filter((w) => w.length >= 2 && !STOP.has(w));
  if (!words.length) return String(total);
  const inCv = words.some((w) => cv.includes(w) || (w.length >= 6 && cv.includes(w.replace(/(ing|s)$/, ''))));
  return inCv ? String(total) : other;
}

const cacheKey = (f) => norm(f.question) + '|' + f.kind + '|' + (f.options || []).map(norm).join(',');

function firstJson(text) {
  const s = String(text || '');
  for (const [open, close] of [['{', '}'], ['[', ']']]) {
    const i = s.indexOf(open), j = s.lastIndexOf(close);
    if (i >= 0 && j > i) { try { return JSON.parse(s.slice(i, j + 1)); } catch (e) { /* try the other shape */ } }
  }
  return null;
}

/** Answers for the fields of one form step. Returns {answers: {key: value}, learned: {cacheKey: value}}. */
export async function answerFields(fields, ctx, data, user, cache, chat) {
  const answers = {};
  const learned = {};
  const ask = [];
  for (const f of fields) {
    const key = cacheKey(f);
    const retry = !!f.error;
    if (f.kind === 'text' && /^(how many|how much)\b|\b(salary|compensation)\b/i.test(f.question)) f.kind = 'numeric';   // digits only
    let a = retry ? null : fit(f, experienceYears(f, data));
    if (a !== null) { answers[f.key] = a; continue; }
    if (!retry && cache[key] !== undefined) a = fit(f, cache[key]);
    if (a === null && !retry) {
      const fact = (data.facts || []).find((x) => norm(x.label) === norm(f.question) || (x.asked || []).some((k) => norm(k) === norm(f.question)));
      if (fact) a = fit(f, fact.value);
    }
    if (a === null && !retry) a = fit(f, ruleAnswer(f, data, user));
    if (a === null && retry) a = fit(f, phoneAgain(f, data));
    if (a !== null) answers[f.key] = a;
    else ask.push(f);
  }
  const stats = { questions: fields.length, own: fields.length - ask.length, ai: ask.length, aiGot: 0 };
  if (!ask.length) return { answers, learned, stats };

  const known = (data.facts || []).slice(0, 80).map((x) => '- ' + x.label + ': ' + x.value).join('\n');
  const list = ask.map((f, i) => {
    const o = { n: i + 1, question: f.question, type: f.kind };
    if (f.options && f.options.length) o.options = f.options.slice(0, 60);
    if (f.maxlen > 0) o.max_length = f.maxlen;
    if (f.placeholder) o.placeholder = f.placeholder;
    if (f.input === 'date') o.format = 'YYYY-MM-DD';
    if (f.error) { o.rejected_answer = f.rejected || ''; o.rejected_with = f.error; }
    return o;
  });
  const parts = [
    'JOB: ' + (ctx.title || '') + (ctx.company ? ' at ' + ctx.company : ''),
    ctx.description ? 'JOB DESCRIPTION:\n' + String(ctx.description).slice(0, 3000) : '',
    'CANDIDATE NOTES:\n' + notes(data, user) + (data.about ? '\n' + String(data.about).slice(0, 1500) : ''),
    known ? 'KNOWN ANSWERS:\n' + known : '',
    'CV:\n' + String(data.cvText || '').slice(0, 9000),
    'QUESTIONS:\n' + JSON.stringify(list),
    'RULES: type "choice": reply with exactly one of the options, copied as written. type "multi": a list of options. '
      + 'type "numeric": digits only. type "text": one short line. type "textarea": 2 to 5 sentences in first person, '
      + 'specific to this job and the CV (a cover letter: at most 150 words). A question with "rejected_with" needs a '
      + 'different answer that satisfies that message. Answer every question.',
    'Return ONLY {"answers": [{"n": <number>, "answer": ...}, ...]}',
  ].filter(Boolean);
  const r = await chat([{ role: 'system', content: SYSTEM }, { role: 'user', content: parts.join('\n\n') }], Math.min(4000, 900 + ask.length * 250));
  const content = (r && (r.content || r.text || (r.choices && r.choices[0] && r.choices[0].message && r.choices[0].message.content))) || '';
  const parsed = firstJson(content);
  let rows = Array.isArray(parsed) ? parsed : ((parsed && parsed.answers) || []);
  if (!rows.length) {          // a reply that was cut off: take the answers that did arrive
    rows = [...String(content).matchAll(/\{\s*"n"\s*:\s*(\d+)\s*,\s*"answer"\s*:\s*("(?:[^"\\]|\\.)*"|\[[^\]]*\]|[^,}\s]+)\s*\}/g)]
      .map((x) => { try { return { n: Number(x[1]), answer: JSON.parse(x[2]) }; } catch (e) { return null; } }).filter(Boolean);
  }
  if (!rows.length) stats.note = content ? 'the AI reply could not be read' : 'the AI sent an empty reply';
  for (const row of rows) {
    const f = ask[Number(row && row.n) - 1];
    if (!f) continue;
    const a = fit(f, row.answer);
    if (a === null) continue;
    answers[f.key] = a;
    learned[cacheKey(f)] = a;
    stats.aiGot += 1;
  }
  return { answers, learned, stats };
}
