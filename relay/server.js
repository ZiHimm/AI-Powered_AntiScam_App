#!/usr/bin/env node
'use strict';
/*
 * Family Verify relay: passes messages between the three phones.
 * No dependencies. Run on a laptop on the same Wi-Fi / hotspot as the phones:
 *
 *   node relay/server.js          (port 8787)
 *   node relay/server.js 9000
 *
 * Phones open an event stream (GET /events?role=mum|son|daughter) and post messages (POST /send).
 * Messages are already signed on the phones; the relay can't forge them and doesn't store them,
 * except the latest public-key "hello" per phone so a phone that joins late still gets the keys.
 * It also serves the app itself, so http://<laptop>:8787/?role=son works in a desktop browser for testing.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = Number(process.argv[2]) || 8787;
const ROOT = path.resolve(__dirname, '..');
const ROLES = new Set(['mum', 'son', 'daughter']);
const clients = new Set(); // { role, res }
const hellos = {};         // role -> latest hello message
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.bin': 'application/octet-stream', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };

const push = (res, msg) => res.write(`data: ${JSON.stringify(msg)}\n\n`);
const online = () => [...new Set([...clients].map((c) => c.role))].join(', ') || 'none';
const stamp = () => new Date().toLocaleTimeString();

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > 1e6) req.destroy(); });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
  const url = new URL(req.url, 'http://x');

  // phone subscribes to its messages
  if (url.pathname === '/events') {
    const role = url.searchParams.get('role');
    if (!ROLES.has(role)) { res.writeHead(400); return res.end('bad role'); }
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    res.write(': connected\n\n');
    const client = { role, res };
    clients.add(client);
    for (const [r, h] of Object.entries(hellos)) if (r !== role) push(res, { ...h, to: role });
    const beat = setInterval(() => res.write(': beat\n\n'), 15000);
    console.log(`${stamp()}  + ${role} connected      (online: ${online()})`);
    req.on('close', () => {
      clearInterval(beat);
      clients.delete(client);
      console.log(`${stamp()}  - ${role} disconnected   (online: ${online()})`);
    });
    return;
  }

  // phone sends a message to another phone (or 'all')
  if (url.pathname === '/send' && req.method === 'POST') {
    let msg;
    try { msg = JSON.parse(await readBody(req)); } catch { res.writeHead(400); return res.end('bad json'); }
    if (msg.t === 'hello' && ROLES.has(msg.from)) hellos[msg.from] = msg;
    let delivered = 0;
    for (const c of clients) {
      if (msg.to === 'all' ? c.role !== msg.from : c.role === msg.to) { push(c.res, { ...msg, to: c.role }); delivered++; }
    }
    console.log(`${stamp()}    ${msg.from} -> ${msg.to}: ${msg.t}${delivered ? '' : '  (not connected!)'}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ delivered }));
  }

  if (url.pathname === '/status') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ online: [...clients].map((c) => c.role) }));
  }

  // static files (the app itself) for desktop-browser testing
  const rel = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname);
  const file = path.join(ROOT, rel);
  if (!file.startsWith(ROOT + path.sep)) { res.writeHead(403); return res.end(); }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end('not found'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
  console.log('\nFamily Verify relay is running.\n');
  console.log('Type ONE of these into each phone\'s "Relay address" box (use the one on the phones\' Wi-Fi):');
  for (const ip of ips) console.log(`   http://${ip}:${PORT}`);
  console.log(`\nDesktop test: http://localhost:${PORT}/?role=mum  (and ?role=son, ?role=daughter in other tabs)\n`);
});
