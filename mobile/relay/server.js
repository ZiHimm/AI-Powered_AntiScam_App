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
 *
 * Family rooms (for hosting on the internet): wss://<host>/f/<FAMILY-CODE>/ws is its own
 * room with its own mum/son/daughter slots and monitors. Plain /ws is the "default" room,
 * so the laptop setup works exactly as before. The code is the only thing keeping strangers
 * out of a family, so use long random codes (see `node relay/server.js --new-code`).
 * On the internet, set MONITOR_TOKEN: without it a monitor is read-only (no inject).
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = +(process.env.PORT || process.argv[2] || 8000);
const WWW = path.join(__dirname, '..', 'www');
const ROLES = ['mum', 'son', 'daughter'];
const MONITOR_TOKEN = process.env.MONITOR_TOKEN || '';
const LIMITS = {
  payload: 64 * 1024,   // biggest real message is a face embedding (~3 KB)
  perRoom: 12,          // 3 phones + monitors + reconnect overlap
  rooms: 500,
  // A whole family (and the laptop) often shares ONE public IP: hotspot, venue Wi-Fi, carrier NAT.
  // So the real limits are per connection and per room; per IP is only a loose global cap.
  msgRate: 20,          // messages per second per connection (token bucket)...
  msgBurst: 60,         // ...with this much burst
  ipMsgRate: 200,       // loose cap per IP
  ipMsgBurst: 400,
  roomConnPerMin: 240,  // new connections per room per minute (all phones in reconnect loops ≈ 150/min)
  ipConnPerMin: 600,    // loose cap per IP
};
// Close codes when refusing (both apps reconnect on anything except 4000, so never use 4000 here)
const CLOSE = { rate: [4029, 'rate limited'], roomFull: [4008, 'room full'], relayFull: [4013, 'relay full'] };

function newCode() {   // e.g. K7Q2-9XPD-M4TR: 12 chars from 32 symbols (60 bits), no 0/O/1/I mix-ups
  const abc = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const c = Array.from(crypto.randomBytes(12), (b) => abc[b % 32]).join('');
  return c.match(/.{4}/g).join('-');
}
if (process.argv.includes('--new-code')) { console.log(newCode()); process.exit(0); }

const PUBLIC_HOST = !!(process.env.FLY_APP_NAME || process.env.RAILWAY_ENVIRONMENT);
// On the internet, plain /ws would be one room shared with every stranger: family codes only.
const DEFAULT_ROOM = !PUBLIC_HOST || process.env.ALLOW_DEFAULT_ROOM === '1';
// On a cloud host the scammer console must not be open to the world.
if (!MONITOR_TOKEN && PUBLIC_HOST) {
  console.error('MONITOR_TOKEN is not set. Refusing to start on a public host (the scammer console would be open to anyone).');
  process.exit(1);
}

// ---------------------------------------------------------------- paths and rooms
// "/ws" and "/monitor.html" → default room. "/f/K7Q2-9XPD/ws" → room K7Q2-9XPD.
const ROOM_PATH = /^\/f\/([A-Za-z0-9-]{1,64})(\/.*)?$/;
function parsePath(p) {
  const m = ROOM_PATH.exec(p);
  if (!m) {
    if (p.startsWith('/f/') || p === '/f') return null;
    return { room: DEFAULT_ROOM ? 'default' : null, rest: p, base: '' };   // static files still served
  }
  // hyphens are only for readability: K7Q2-9XPD and k7q29xpd are the same room (a typo can't split a family)
  const code = m[1].toUpperCase().replace(/-/g, '');
  if (code.length < 8) return null;   // too short to be unguessable
  return { room: code, rest: m[2] || '', base: `/f/${m[1]}` };
}
const mask = (room) => (room === 'default' ? 'default room' : `room ${room.slice(0, 4)}…`);

const rooms = new Map();   // code -> { devices: role->ws, hellos: role->{pub,name,at}, monitors, conns }
function getRoom(code) {
  let r = rooms.get(code);
  if (!r) rooms.set(code, (r = { code, devices: new Map(), hellos: {}, monitors: new Set(), conns: new Set() }));
  return r;
}

// ---------------------------------------------------------------- limits
function ipOf(req) {
  // behind Fly/Railway the TCP peer is the proxy; the client IP is in a header
  const h = req.headers['fly-client-ip'] || (req.headers['x-forwarded-for'] || '').split(',')[0].trim();
  return h || req.socket.remoteAddress || '?';
}
const bucket = (burst) => ({ tokens: burst, at: Date.now() });
function take(b, rate, burst) {   // token bucket
  const t = Date.now();
  b.tokens = Math.min(burst, b.tokens + ((t - b.at) / 1000) * rate);
  b.at = t;
  if (b.tokens < 1) return false;
  b.tokens -= 1;
  return true;
}
const ipBuckets = new Map();   // ip -> message bucket
const connLog = new Map();     // "room:<code>" / "ip:<addr>" -> timestamps of accepted connections (last minute)
const recent = (key) => (connLog.get(key) || []).filter((x) => Date.now() - x < 60000);
// Only ACCEPTED connections count, so refusals can't keep a family locked out.
function allowConnection(room, ip) {
  const r = recent(`room:${room}`), i = recent(`ip:${ip}`);
  if (r.length >= LIMITS.roomConnPerMin || i.length >= LIMITS.ipConnPerMin) return false;
  r.push(Date.now()); i.push(Date.now());
  connLog.set(`room:${room}`, r); connLog.set(`ip:${ip}`, i);
  return true;
}
function allowMessage(ws) {
  if (!ipBuckets.has(ws.ip)) ipBuckets.set(ws.ip, bucket(LIMITS.ipMsgBurst));
  return take(ws.bucket, LIMITS.msgRate, LIMITS.msgBurst) && take(ipBuckets.get(ws.ip), LIMITS.ipMsgRate, LIMITS.ipMsgBurst);
}
function tokenOk(given) {
  if (!MONITOR_TOKEN) return true;   // laptop relay: the presenter's own machine
  const a = crypto.createHash('sha256').update(String(given || '')).digest();
  const b = crypto.createHash('sha256').update(MONITOR_TOKEN).digest();
  return crypto.timingSafeEqual(a, b);
}

// ---------------------------------------------------------------- static files
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.bin': 'application/octet-stream', '.png': 'image/png', '.svg': 'image/svg+xml',
};
const started = Date.now();
const server = http.createServer((req, res) => {
  let p;
  try { p = decodeURIComponent(new URL(req.url, 'http://x').pathname); } catch { res.writeHead(400).end(); return; }
  if (p === '/healthz') {
    let phones = 0;
    rooms.forEach((r) => { phones += r.devices.size; });
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ ok: true, uptimeSec: Math.round((Date.now() - started) / 1000), rooms: rooms.size, phones, monitorLocked: !!MONITOR_TOKEN }));
    return;
  }
  const where = parsePath(p);
  if (!where) { res.writeHead(404).end('not found'); return; }
  if (where.base && !where.rest) { res.writeHead(301, { Location: `${where.base}/` }).end(); return; }  // relative links need the slash
  p = where.rest === '/' ? '/index.html' : where.rest;
  const file = path.normalize(path.join(WWW, p));
  if (!file.startsWith(WWW)) { res.writeHead(403).end(); return; }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404).end('not found'); return; }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(data);
  });
});

// ---------------------------------------------------------------- relay
const wss = new WebSocketServer({ noServer: true, maxPayload: LIMITS.payload });

const ts = () => new Date().toLocaleTimeString();
const send = (ws, obj) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); };
const toMonitors = (room, obj) => room.monitors.forEach((m) => send(m, obj));

function peers(room) {
  const out = {};
  // only advertise keys of phones that are connected right now, so a stale key from an
  // earlier session (e.g. before a reinstall) is never handed out and pinned by mistake
  for (const r of ROLES) {
    const on = room.devices.has(r);
    out[r] = { online: on, pub: on ? room.hellos[r]?.pub || null : null, name: room.hellos[r]?.name || null };
  }
  return out;
}
function broadcastPeers(room) {
  const msg = { t: 'peers', peers: peers(room) };
  room.devices.forEach((ws) => send(ws, msg));
  toMonitors(room, msg);
}

function route(room, fromRole, to, body, { injected = false } = {}) {
  const target = room.devices.get(to);
  const delivered = !!(target && target.readyState === 1);
  if (delivered) send(target, { t: 'msg', from: fromRole, body });
  toMonitors(room, { t: 'traffic', from: fromRole, to, body, delivered, injected, at: Date.now() });
  return delivered;
}

function refuse(socket, status, text) {
  socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  socket.destroy();
}
server.on('upgrade', (req, socket, head) => {
  let where = null;
  try { where = parsePath(new URL(req.url, 'http://x').pathname); } catch { /* bad URL */ }
  if (!where || !where.room || where.rest !== '/ws') return refuse(socket, 404, 'Not Found');
  const ip = ipOf(req);
  // refuse with a WebSocket close code + reason (not a bare HTTP error), so the apps' logs say why
  const closeWith = ([code, reason]) => {
    console.log(`[${ts()}] refused ${mask(where.room)} (${ip}): ${reason}`);
    wss.handleUpgrade(req, socket, head, (ws) => { ws.on('error', () => {}); ws.close(code, reason); });
  };
  if (!rooms.has(where.room) && rooms.size >= LIMITS.rooms) return closeWith(CLOSE.relayFull);
  if ((rooms.get(where.room)?.conns.size || 0) >= LIMITS.perRoom) return closeWith(CLOSE.roomFull);
  if (!allowConnection(where.room, ip)) return closeWith(CLOSE.rate);
  const room = getRoom(where.room);
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.room = room;
    ws.ip = ip;
    ws.bucket = bucket(LIMITS.msgBurst);
    room.conns.add(ws);
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.role = null;
  ws.on('pong', () => { ws.isAlive = true; });
  // a bad frame (e.g. over the size limit) closes this one connection; without a handler it would crash the relay
  ws.on('error', () => {});
  const { room, ip } = ws;

  ws.on('message', (raw) => {
    if (!allowMessage(ws)) {   // over the rate limit: drop, and say so (at most every 5 s)
      if (Date.now() - (ws.warnedAt || 0) > 5000) {
        ws.warnedAt = Date.now();
        send(ws, { t: 'error', code: CLOSE.rate[0], reason: 'rate limited: messages dropped' });
        console.log(`[${ts()}] rate limited ${ws.role || 'unknown'} in ${mask(room.code)} (${ip})`);
      }
      return;
    }
    let m;
    try { m = JSON.parse(raw); } catch { return; }

    if (m.t === 'hello') {
      if (m.role === 'monitor') {
        ws.role = 'monitor';
        ws.canInject = tokenOk(m.token);
        room.monitors.add(ws);
        send(ws, { t: 'peers', peers: peers(room) });
        send(ws, { t: 'monitor', canInject: ws.canInject });
        console.log(`[${ts()}] monitor connected, ${mask(room.code)}${ws.canInject ? '' : ' (read-only)'} (${ip})`);
        return;
      }
      if (!ROLES.includes(m.role)) return;
      const old = room.devices.get(m.role);
      if (old && old !== ws) { old.role = null; old.close(4000, 'replaced by a newer connection'); }
      ws.role = m.role;
      room.devices.set(m.role, ws);
      room.hellos[m.role] = { pub: m.pub, name: m.name || m.role, at: Date.now() };
      console.log(`[${ts()}] ${m.role} online, ${mask(room.code)} (${ip})`);
      broadcastPeers(room);
      return;
    }

    if (m.t === 'msg' && ws.role && ws.role !== 'monitor') {
      const delivered = route(room, ws.role, m.to, m.body);
      if (!delivered) send(ws, { t: 'undeliverable', to: m.to, kind: m.body?.kind });
      return;
    }

    // scammer console + presenter: injects messages claiming to come from someone else.
    // Only for monitors that sent the right MONITOR_TOKEN (or any monitor on the laptop relay).
    if (m.t === 'inject' && ws.role === 'monitor') {
      if (!ws.canInject) { send(ws, { t: 'monitor', canInject: false }); return; }
      route(room, m.from || 'mum', m.to, m.body, { injected: true });
      return;
    }

    if (m.t === 'log' && ws.role) {
      toMonitors(room, { t: 'log', role: ws.role, tag: m.tag, html: m.html, at: Date.now() });
    }
  });

  ws.on('close', () => {
    room.conns.delete(ws);
    if (ws.role === 'monitor') room.monitors.delete(ws);
    else if (ws.role && room.devices.get(ws.role) === ws) {
      room.devices.delete(ws.role);
      console.log(`[${ts()}] ${ws.role} offline, ${mask(room.code)}`);
      broadcastPeers(room);
    }
    if (!room.conns.size) rooms.delete(room.code);   // empty room: forget it (and its keys)
  });
});

// Detect phones that vanish without closing (airplane mode, Wi-Fi off) within ~8 s
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) { ws.terminate(); return; }
    ws.isAlive = false;
    try { ws.ping(); } catch { /* ignore */ }
  });
  const t = Date.now();
  connLog.forEach((a, k) => { if (!a.some((x) => t - x < 60000)) connLog.delete(k); });
  ipBuckets.forEach((b, ip) => { if (t - b.at > 120000) ipBuckets.delete(ip); });
}, 4000);

server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
  console.log(`\nFamily Verify relay on port ${PORT}`);
  console.log(`  Monitor (projector):  http://localhost:${PORT}/monitor.html`);
  console.log(`  Browser test:         http://localhost:${PORT}/?role=mum  (also son, daughter)`);
  console.log(`  Phones connect to:    ${ips.map((ip) => `ws://${ip}:${PORT}`).join('   ')}`);
  console.log(`  Family room example:  ws://<this host>:${PORT}/f/${newCode()}   (any code of 8+ letters/digits)`);
  console.log(`  Scammer console:      ${MONITOR_TOKEN ? 'locked (monitor needs #token=… in its URL)' : 'open to any monitor (fine on a laptop; set MONITOR_TOKEN on the internet)'}\n`);
});
