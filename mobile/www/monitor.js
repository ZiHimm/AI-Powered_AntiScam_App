'use strict';
// Laptop monitor: shows every phone's log, lets the presenter drive Mum's phone,
// and plays the scammer (forged + replayed alerts) to prove signatures and nonces work.
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const NAMES = { mum: '👵 Mum', son: '👦 Son', daughter: '👩 Daughter', relay: 'relay', scammer: '😈 scammer' };
let ws, peers = {}, captured = null, canInject = true;
// Works on the laptop relay (ws://host/ws) and on a hosted relay inside a family room
// (https://host/f/<CODE>/monitor.html → wss://host/f/<CODE>/ws). The token that unlocks the
// presenter/scammer buttons goes after # so it never reaches server logs: …/monitor.html#token=…
const ROOM_BASE = location.pathname.replace(/\/[^/]*$/, '');
const WS_URL = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}${ROOM_BASE}/ws`;
const TOKEN = new URLSearchParams(location.hash.slice(1)).get('token') || '';

// Phones send log lines as HTML. Any device that knows the room code can send one, so never run it:
// only these tags survive, with no attributes; everything else becomes plain text.
const SAFE = new Set(['B', 'I', 'CODE', 'BR', 'SMALL']);
function safeHtml(html) {
  const src = new DOMParser().parseFromString(`<div>${String(html ?? '')}</div>`, 'text/html').body.firstChild;
  const out = document.createElement('div');
  (function copy(from, to) {
    for (const n of from.childNodes) {
      if (n.nodeType === Node.TEXT_NODE) to.appendChild(document.createTextNode(n.textContent));
      else if (n.nodeType === Node.ELEMENT_NODE) {
        if (SAFE.has(n.tagName)) copy(n, to.appendChild(document.createElement(n.tagName)));
        else copy(n, to);   // unknown tag: keep its text, drop the tag
      }
    }
  })(src, out);
  return out.innerHTML;
}

function line(who, tag, html) {
  const li = document.createElement('li');
  if (tag === 'sep') {
    li.className = 'sep';
    li.innerHTML = `── ${safeHtml(html)} ──`;
  } else {
    li.innerHTML = `<time>${new Date().toLocaleTimeString()}</time><span class="who who-${esc(who)}">${esc(NAMES[who] || who)}</span>`
      + `<span class="tag tag-${esc(tag)}">${esc(tag)}</span><span class="msg">${safeHtml(html)}</span>`;
  }
  const ol = $('#log');
  ol.prepend(li);
  while (ol.children.length > 400) ol.lastChild.remove();
}

function renderDevs() {
  $('#devs').innerHTML = ['mum', 'son', 'daughter'].map((r) => {
    const p = peers[r] || {};
    return `<div class="dev ${p.online ? 'on' : 'off'}"><span>${NAMES[r]}<small>${p.pub ? `key ${esc(String(p.pub.x).slice(0, 10))}…` : 'not paired'}</small></span>`
      + `<span class="st">● ${p.online ? 'online' : 'offline'}</span></div>`;
  }).join('');
}

function connect() {
  ws = new WebSocket(WS_URL);
  ws.onopen = () => {
    ws.send(JSON.stringify({ t: 'hello', role: 'monitor', token: TOKEN }));
    $('#conn').className = 'pill pill-ok';
    $('#conn').textContent = 'Relay connected';
  };
  ws.onclose = () => {
    $('#conn').className = 'pill pill-err';
    $('#conn').textContent = 'Relay disconnected';
    setTimeout(connect, 1500);
  };
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.t === 'peers') {
      for (const r of Object.keys(m.peers)) {
        if (peers[r] && peers[r].online !== m.peers[r].online) line('relay', m.peers[r].online ? 'info' : 'warn', `${NAMES[r]}'s phone ${m.peers[r].online ? 'connected' : '<b>disconnected</b>'}`);
      }
      peers = m.peers;
      renderDevs();
    } else if (m.t === 'log') {
      line(m.role, m.tag, m.html); // device-generated HTML from our own app
    } else if (m.t === 'traffic') {
      onTraffic(m);
    } else if (m.t === 'monitor') {
      canInject = m.canInject;
      $('#conn').textContent = canInject ? 'Relay connected' : 'Relay connected · read-only';
      if (!canInject) line('relay', 'warn', 'Read-only monitor: presenter and scammer buttons are off. Open this page with <code>#token=…</code> to unlock them.');
    }
  };
}

function onTraffic(m) {
  // the scammer sniffs the network: keep a copy of the latest urgent alert
  if (m.body?.kind === 'alert' && !m.injected) {
    try {
      const p = JSON.parse(m.body.env.body);
      if (p.level === 'urgent') {
        captured = { env: m.body.env, to: m.to, title: p.title };
        $('#captured').innerHTML = `Captured: “${esc(p.title)}” (nonce <code>${esc(p.nonce)}</code>)`;
      }
    } catch { /* ignore */ }
  }
  if (!m.delivered && m.body?.kind !== 'demo') line('relay', 'warn', `✗ ${esc(m.from)} → ${esc(m.to)} "${esc(m.body?.kind)}" not delivered (phone offline)`);
  if ($('#show-raw').checked) {
    line('relay', 'push', `${esc(m.from)} → ${esc(m.to)}${m.injected ? ' <b>[injected]</b>' : ''}: <code>${esc(JSON.stringify(m.body).slice(0, 220))}</code>`);
  }
}

const inject = (to, body, from = 'mum') => {
  if (!canInject) { line('relay', 'warn', 'Read-only monitor: nothing was sent.'); return; }
  ws.send(JSON.stringify({ t: 'inject', from, to, body }));
};
const members = () => ['son', 'daughter'].filter((r) => peers[r]?.online);

document.querySelectorAll('[data-demo]').forEach((b) => b.addEventListener('click', () => {
  const action = b.dataset.demo;
  const targets = action === 'reset' ? ['mum', 'son', 'daughter'] : ['mum'];
  targets.forEach((t) => inject(t, { kind: 'demo', action }, 'presenter'));
  line('relay', 'info', `Presenter: ${esc(action)}`);
}));

// forged alert: looks exactly like Mum's, but signed with the scammer's own key
let attackerKey = null;
$('#btn-forge').addEventListener('click', async () => {
  if (!crypto.subtle) { line('scammer', 'warn', 'Open this page via http://localhost:8000/monitor.html (WebCrypto needs localhost).'); return; }
  attackerKey ||= await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  const nonce = [...crypto.getRandomValues(new Uint8Array(12))].map((b) => b.toString(16).padStart(2, '0')).join('');
  const payload = {
    type: 'guardian-alert', level: 'urgent', about: null, callId: 'fake',
    title: '🚨 Mum is in hospital!', body: 'Transfer RM 5,000 to account 8812-3345-01 now for her treatment.',
    from: 'mum', ts: Date.now(), nonce,
  };
  const body = JSON.stringify(payload);
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, attackerKey.privateKey, new TextEncoder().encode(body));
  const env = { body, sig: btoa(String.fromCharCode(...new Uint8Array(sig))) };
  const to = members();
  if (!to.length) { line('scammer', 'warn', 'No family phones online to attack.'); return; }
  line('scammer', 'attack', `😈 Sends a fake "Mum is in hospital, send RM 5,000" alert to ${to.map((r) => NAMES[r]).join(' + ')}, pretending to be Mum's phone, signed with the scammer's own key.`);
  to.forEach((r) => inject(r, { kind: 'alert', env }));
});

$('#btn-replay').addEventListener('click', () => {
  if (!captured) { line('scammer', 'warn', 'No urgent alert captured yet. Run the "Son taps No" scenario first.'); return; }
  const to = members();
  line('scammer', 'attack', `🔁 Re-sends the real alert it captured earlier (“${esc(captured.title)}”) to ${to.map((r) => NAMES[r]).join(' + ')}. Replay attack.`);
  to.forEach((r) => inject(r, { kind: 'alert', env: captured.env }));
});

$('#btn-clear').addEventListener('click', () => { $('#log').innerHTML = ''; });
renderDevs();
connect();
