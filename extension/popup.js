/* JSTB extension: the small window of the toolbar button. */
const $ = (id) => document.getElementById(id);
const send = (msg) => new Promise((resolve) => chrome.runtime.sendMessage(msg, (r) => resolve(chrome.runtime.lastError ? null : r)));
const FIELDS = ['perHour', 'perDay', 'minScore'];
const CHECKS = ['linkedin', 'wuzzuf', 'company', 'noSubmit'];
let shownSettings = false;
let note = '';

function time(at) {
  const d = new Date(at * 1000);
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

async function refresh() {
  const s = await send({ t: 'state' });
  if (!s) return;
  $('signedOut').hidden = s.signedIn;
  $('signedIn').hidden = !s.signedIn;
  $('who').textContent = s.signedIn ? ((s.user && s.user.email) || 'Signed in') + (s.user && s.user.pro ? ' (Pro)' : '') : 'Not signed in';
  if (!s.signedIn) {
    $('signin').disabled = s.signingIn;
    $('signinNote').textContent = s.signingIn ? 'Finish signing in on the Google page that opened...' : (note || (s.message !== 'Not started' ? s.message : ''));
    return;
  }
  $('today').textContent = s.today + ' / ' + s.caps.perDay;
  $('hour').textContent = s.hour + ' / ' + s.caps.perHour;
  $('queue').textContent = s.queue;
  let msg = note || s.message || '';
  if (s.running && !s.active && s.nextAt > Date.now() / 1000) {
    msg += ' (next in ' + Math.max(1, Math.round((s.nextAt - Date.now() / 1000) / 60)) + ' min)';
  }
  $('message').textContent = msg;
  $('toggle').textContent = s.running ? 'Stop' : 'Start';
  $('toggle').classList.toggle('stop', s.running);
  if (!shownSettings) {
    for (const k of FIELDS) $(k).value = s.settings[k];
    for (const k of CHECKS) $(k).checked = !!s.settings[k];
    shownSettings = true;
  }
  const box = $('log');
  const atEnd = box.scrollTop + box.clientHeight >= box.scrollHeight - 8;
  box.textContent = '';
  for (const l of s.log) {
    const row = document.createElement('div');
    const t = document.createElement('time');
    t.textContent = time(l.at);
    row.append(t, document.createTextNode(l.text));
    box.append(row);
  }
  if (!s.log.length) box.textContent = 'Nothing yet.';
  if (atEnd) box.scrollTop = box.scrollHeight;
}

$('signin').addEventListener('click', async () => {
  $('signin').disabled = true;
  const r = await send({ t: 'signin' });
  note = r && r.ok ? '' : ((r && r.message) || 'Could not start signing in.');
  refresh();
});
$('toggle').addEventListener('click', async () => {
  $('toggle').disabled = true;
  const running = $('toggle').textContent === 'Stop';
  const r = await send({ t: running ? 'stop' : 'start' });
  note = r && r.ok === false ? r.message : '';
  $('toggle').disabled = false;
  refresh();
});
$('save').addEventListener('click', async () => {
  const settings = {};
  for (const k of FIELDS) settings[k] = $(k).value;
  for (const k of CHECKS) settings[k] = $(k).checked;
  await send({ t: 'settings', settings });
  shownSettings = false;
  $('settingsBox').open = false;
  refresh();
});
$('signout').addEventListener('click', async (e) => { e.preventDefault(); await send({ t: 'signout' }); note = ''; refresh(); });
$('answers').addEventListener('click', (e) => { e.preventDefault(); chrome.runtime.openOptionsPage(); });
$('clear').addEventListener('click', async (e) => { e.preventDefault(); await send({ t: 'clear_log' }); refresh(); });

refresh();
setInterval(refresh, 2000);
