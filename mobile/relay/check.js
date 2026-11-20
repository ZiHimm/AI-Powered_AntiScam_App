'use strict';
// Quick self-check of a running relay: rooms, monitor token, limits, health.
//   node relay/check.js http://localhost:8000 <MONITOR_TOKEN>
//   node relay/check.js https://your-relay.fly.dev <MONITOR_TOKEN>
// Run it before going on stage. It uses throwaway room codes, so it never disturbs a real family room.
const WebSocket = require('ws');
const crypto = require('crypto');

const BASE = (process.argv[2] || 'http://localhost:8000').replace(/\/+$/, '');
const TOKEN = process.argv[3] || '';
const WS = BASE.replace(/^http/, 'ws');
const IP = `198.51.100.${crypto.randomInt(1, 254)}`;   // fake client IP per run, so reruns don't trip the per-IP limit locally
const code = () => `TEST-${crypto.randomBytes(5).toString('hex').toUpperCase()}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failed = 0;
const ok = (cond, what) => { console.log(`${cond ? '  ok ' : 'FAIL '} ${what}`); if (!cond) failed++; };

function open(path, hello) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`${WS}${path}`, { headers: { 'x-forwarded-for': IP } });
    ws.inbox = [];
    ws.on('message', (d) => ws.inbox.push(JSON.parse(d)));
    ws.on('open', () => { if (hello) ws.send(JSON.stringify(hello)); setTimeout(() => resolve(ws), 150); });
    ws.on('unexpected-response', (_req, res) => { ws.status = res.statusCode; resolve(ws); });
    ws.closed = new Promise((done) => ws.on('close', (c, r) => { ws.closeCode = c; ws.closeReason = String(r); done(); }));
    ws.on('error', () => {});
  });
}
// wait for the close event, not a fixed time: behind Fly's proxy it fires ~5 s after the close code is sent
const closed = (ws, ms = 10000) => Promise.race([ws.closed, sleep(ms)]);
const pub = { kty: 'EC', crv: 'P-256', x: 'test', y: 'test' };
const phone = (room, role) => open(`/f/${room}/ws`, { t: 'hello', role, pub, name: role });
const lastPeers = (ws) => [...ws.inbox].reverse().find((m) => m.t === 'peers')?.peers;
const got = (ws, kind) => ws.inbox.some((m) => m.t === 'msg' && m.body?.kind === kind);

(async () => {
  console.log(`Checking ${BASE}`);
  const h = await fetch(`${BASE}/healthz`).then((r) => r.json()).catch(() => null);
  ok(h && h.ok, `/healthz answers (${h ? `${h.rooms} rooms, ${h.phones} phones, scammer console ${h.monitorLocked ? 'locked' : 'OPEN'}` : 'no answer'})`);
  if (!h) process.exit(1);

  const A = code(), B = code();
  const [mum, son, daughter] = [await phone(A, 'mum'), await phone(A, 'son'), await phone(A, 'daughter')];
  const otherSon = await phone(B, 'son');
  await sleep(200);
  const pa = lastPeers(mum), pb = lastPeers(otherSon);
  ok(pa?.mum.online && pa.son.online && pa.daughter.online, 'room A: all three phones see each other');
  ok(pb?.son.online && !pb.mum.online && !pb.daughter.online, "room B: sees only its own phone, nothing from room A");

  son.send(JSON.stringify({ t: 'msg', to: 'mum', body: { kind: 'reply' } }));
  otherSon.send(JSON.stringify({ t: 'msg', to: 'mum', body: { kind: 'reply' } }));
  await sleep(300);
  ok(mum.inbox.filter((m) => m.t === 'msg').length === 1, 'room A message reaches room A only');
  ok(otherSon.inbox.some((m) => m.t === 'undeliverable'), 'room B gets "undeliverable" (no mum in room B)');

  const viewer = await open(`/f/${A}/ws`, { t: 'hello', role: 'monitor' });
  viewer.send(JSON.stringify({ t: 'inject', from: 'mum', to: 'daughter', body: { kind: 'forged' } }));
  await sleep(300);
  if (h.monitorLocked) {
    ok(viewer.inbox.some((m) => m.t === 'monitor' && m.canInject === false), 'monitor without token is told it is read-only');
    ok(!got(daughter, 'forged'), 'monitor without token cannot inject');
  } else console.log('  --   scammer console is open (laptop mode): token checks skipped');
  if (TOKEN) {
    const presenter = await open(`/f/${A}/ws`, { t: 'hello', role: 'monitor', token: TOKEN });
    presenter.send(JSON.stringify({ t: 'inject', from: 'mum', to: 'daughter', body: { kind: 'demo' } }));
    await sleep(300);
    ok(got(daughter, 'demo'), 'monitor with the token can inject');
    presenter.close();
  }

  const laptop = await open('/ws', { t: 'hello', role: 'daughter', pub, name: 'daughter' });
  await sleep(200);
  if (laptop.status === 404) ok(true, 'plain /ws is refused here (public host: family codes only)');
  else ok(lastPeers(laptop)?.daughter.online && !lastPeers(laptop).mum.online, 'plain /ws (laptop setup) is its own default room');
  laptop.close();

  const son2 = await phone(A, 'son');
  const mumB = await phone(B, 'mum');
  await closed(son);
  ok(son.closeCode === 4000, 'a second "son" replaces the first (close code 4000)');
  ok(mum.readyState === WebSocket.OPEN && mumB.readyState === WebSocket.OPEN, "a Mum in room B does not replace the Mum in room A");

  // per-connection message limit: a flood from one phone is cut, a second phone on the SAME IP is unaffected
  const before = daughter.inbox.filter((m) => m.t === 'msg').length;
  for (let i = 0; i < 300; i++) son2.send(JSON.stringify({ t: 'msg', to: 'daughter', body: { kind: 'flood' } }));
  await sleep(500);
  mumB.send(JSON.stringify({ t: 'msg', to: 'son', body: { kind: 'after-flood' } }));
  await sleep(300);
  const flooded = daughter.inbox.filter((m) => m.t === 'msg').length - before;
  ok(flooded >= 50 && flooded <= 80, `a flood of 300 messages from one phone is cut to the burst (${flooded} delivered)`);
  ok(son2.inbox.some((m) => m.t === 'error' && m.code === 4029), 'the flooding phone is told it is rate limited (4029)');
  ok(otherSon.inbox.some((m) => m.t === 'msg' && m.body?.kind === 'after-flood'), 'another phone on the same IP still gets through');

  // a full room refuses with a clear close code, not a silent drop
  const C = code(), crowd = [];
  for (let i = 0; i < 12; i++) crowd.push(await open(`/f/${C}/ws`));
  const extra = await open(`/f/${C}/ws`);
  await closed(extra);
  ok(extra.closeCode === 4008 && extra.closeReason === 'room full', `13th connection to a room is closed with 4008 "room full" (got ${extra.closeCode})`);
  crowd.forEach((w) => w.close());

  const tail = crypto.randomBytes(4).toString('hex').toUpperCase();
  const typed1 = await phone(`ABCD-${tail}-WXYZ`, 'mum');
  const typed2 = await phone(`abcd${tail.toLowerCase()}wxyz`, 'son');
  await sleep(200);
  ok(lastPeers(typed1)?.son.online, 'hyphens and case are ignored: ABCD-…-WXYZ and abcd…wxyz are the same room');
  typed1.close(); typed2.close();

  const short = await open('/f/SHORT/ws');
  ok(short.status === 404, 'too-short family code is refused');
  ok((await fetch(`${BASE}/f/${A}/monitor.html`)).status === 200, 'monitor page is served inside the room');

  const big = await phone(A, 'daughter');
  big.send('x'.repeat(70 * 1024));
  await closed(big);
  ok(big.closeCode === 1009, 'messages over 64 KB are refused');

  ok((await fetch(`${BASE}/healthz`).catch(() => null))?.ok, 'relay is still up after the oversized message');

  for (const ws of [mum, son2, otherSon, mumB, viewer, big]) ws.close();
  console.log(failed ? `\n${failed} check(s) FAILED` : '\nAll checks passed');
  process.exit(failed ? 1 : 0);
})();
