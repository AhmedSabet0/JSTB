/* JSTB extension: the "My answers" page. */
const KEYS = ['work_start_year', 'default_years', 'expected_salary', 'current_salary', 'currency', 'phone', 'city', 'notice_days',
  'relocate', 'onsite', 'authorized', 'sponsorship', 'graduation_year', 'english', 'driving', 'military', 'gender'];
const $ = (id) => document.getElementById(id);
const send = (msg) => new Promise((resolve) => chrome.runtime.sendMessage(msg, (r) => resolve(chrome.runtime.lastError ? null : r)));

function show(el, value) {
  if (el.tagName !== 'SELECT') { el.value = value; return; }
  const want = String(value).trim().toLowerCase();
  const opt = [...el.options].find((o) => o.value.toLowerCase() === want)
    || (want ? [...el.options].find((o) => o.value && (o.value.toLowerCase().startsWith(want) || want.startsWith(o.value.toLowerCase()))) : null);
  if (opt) el.value = opt.value;
}

(async () => {
  const r = (await send({ t: 'get_setup' })) || { profile: {} };
  for (const k of KEYS) show($(k), r.profile[k] === undefined ? '' : r.profile[k]);
  if (!$('work_start_year').value && r.guessYear) $('work_start_year').value = r.guessYear;
  if (!$('default_years').value) $('default_years').value = 1;
})();

$('save').addEventListener('click', async () => {
  const setup = {};
  for (const k of KEYS) setup[k] = $(k).value.trim();
  await send({ t: 'save_setup', setup });
  $('saved').textContent = 'Saved';
  setTimeout(() => { $('saved').textContent = ''; }, 2500);
});
