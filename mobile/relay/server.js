'use strict';
/*
 * Family Verify relay: stands in for the push network (FCM in production).
 *
 *  - Serves www/ over HTTP so the app can be tested in browser tabs and the
 *    laptop can show monitor.html (network log + scammer console) on the projector.
 *  - WebSocket at /ws: each phone says hello with its role + public key, the relay
 *    forwards messages between roles. It never sees faces or video, only the small
 *    signed payloads, and it does NOT vouch for anything: phones verify signatures
 *    themselves, which is why the scammer console can inject forged/replayed alerts.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { WebSocketServer } = require('ws');

const PORT = +(process.env.PORT || process.argv[2] || 8000);
const WWW = path.join(__dirname, '..', 'www');
const ROLES = ['mum', 'son', 'daughter'];

// ---------------------------------------------------------------- static files
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.bin': 'application/octet-stream', '.png': 'image/png', '.svg': 'image/svg+xml',
};
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (p === '/') p = '/index.html';
  const file = path.normalize(path.join(WWW, p));
  if (!file.startsWith(WWW)) { res.writeHead(403).end(); return; }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404).end('not found'); return; }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(data);
  });
});

// ---------------------------------------------------------------- relay
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 2 * 1024 * 1024 });
const devices = new Map();   // role -> ws
const hellos = {};           // role -> { pub, name, at }
const monitors = new Set();

const ts = () => new Date().toLocaleTimeString();
const send = (ws, obj) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); };
const toMonitors = (obj) => monitors.forEach((m) => send(m, obj));

function peers() {
  const out = {};
  // only advertise keys of phones that are connected right now, so a stale key from an
  // earlier session (e.g. before a reinstall) is never handed out and pinned by mistake
  for (const r of ROLES) {
    const on = devices.has(r);
    out[r] = { online: on, pub: on ? hellos[r]?.pub || null : null, name: hellos[r]?.name || null };
  }
  return out;
}
function broadcastPeers() {
  const msg = { t: 'peers', peers: peers() };
  devices.forEach((ws) => send(ws, msg));
  toMonitors(msg);
}

function route(fromRole, to, body, { injected = false } = {}) {
  const target = devices.get(to);
  const delivered = !!(target && target.readyState === 1);
  if (delivered) send(target, { t: 'msg', from: fromRole, body });
  toMonitors({ t: 'traffic', from: fromRole, to, body, delivered, injected, at: Date.now() });
  return delivered;
}

wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.role = null;
  ws.on('pong', () => { ws.isAlive = true; });
  const ip = req.socket.remoteAddress;

  ws.on('message', (raw) => {
    let m;
    try { m = JSON.parse(raw); } catch { return; }

    if (m.t === 'hello') {
      if (m.role === 'monitor') {
        ws.role = 'monitor';
        monitors.add(ws);
        send(ws, { t: 'peers', peers: peers() });
        console.log(`[${ts()}] monitor connected (${ip})`);
        return;
      }
      if (!ROLES.includes(m.role)) return;
      const old = devices.get(m.role);
      if (old && old !== ws) { old.role = null; old.close(4000, 'replaced by a newer connection'); }
      ws.role = m.role;
      devices.set(m.role, ws);
      hellos[m.role] = { pub: m.pub, name: m.name || m.role, at: Date.now() };
      console.log(`[${ts()}] ${m.role} online (${ip})`);
      broadcastPeers();
      return;
    }

    if (m.t === 'msg' && ws.role && ws.role !== 'monitor') {
      const delivered = route(ws.role, m.to, m.body);
      if (!delivered) send(ws, { t: 'undeliverable', to: m.to, kind: m.body?.kind });
      return;
    }

    // scammer console on the laptop: injects messages claiming to come from someone else
    if (m.t === 'inject' && ws.role === 'monitor') {
      route(m.from || 'mum', m.to, m.body, { injected: true });
      return;
    }

    if (m.t === 'log' && ws.role) {
      toMonitors({ t: 'log', role: ws.role, tag: m.tag, html: m.html, at: Date.now() });
    }
  });

  ws.on('close', () => {
    if (ws.role === 'monitor') { monitors.delete(ws); return; }
    if (ws.role && devices.get(ws.role) === ws) {
      devices.delete(ws.role);
      console.log(`[${ts()}] ${ws.role} offline`);
      broadcastPeers();
    }
  });
});

// Detect phones that vanish without closing (airplane mode, Wi-Fi off) within ~8 s
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) { ws.terminate(); return; }
    ws.isAlive = false;
    try { ws.ping(); } catch { /* ignore */ }
  });
}, 4000);

server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
  console.log(`\nFamily Verify relay on port ${PORT}`);
  console.log(`  Monitor (projector):  http://localhost:${PORT}/monitor.html`);
  console.log(`  Browser test:         http://localhost:${PORT}/?role=mum  (also son, daughter)`);
  console.log(`  Phones connect to:    ${ips.map((ip) => `ws://${ip}:${PORT}`).join('   ')}\n`);
});
