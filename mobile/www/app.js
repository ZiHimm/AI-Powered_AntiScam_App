'use strict';
/*
 * Family Verify: real-phone version
 * ---------------------------------
 * One app, three roles. Each phone is Mum, Son or Daughter. Messages travel over a
 * WebSocket relay on the laptop (stand-in for FCM push). Every device makes its own
 * ECDSA P-256 key pair; public keys are exchanged when a phone joins and pinned
 * (trust on first use), so forged and replayed alerts fail on real devices.
 * Face detection/recognition (face-api.js) runs on Mum's phone; faces never leave it.
 */

// ---------------------------------------------------------------- helpers
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Date.now();
const hhmmss = (t = now()) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const randHex = (bytes = 16) => [...crypto.getRandomValues(new Uint8Array(bytes))].map((b) => b.toString(16).padStart(2, '0')).join('');
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const code = (o) => `<code>${esc(typeof o === 'string' ? o : JSON.stringify(o))}</code>`;

const PEOPLE = {
  mum: { name: 'Mum', emoji: '👵' },
  son: { name: 'Son', emoji: '👦' },
  daughter: { name: 'Daughter', emoji: '👩' },
};
const MEMBERS = { son: PEOPLE.son, daughter: PEOPLE.daughter };
const IDS = Object.keys(MEMBERS);
const nm = (id) => PEOPLE[id]?.name || id;
const MODEL_URL = 'vendor/models';
const isNative = !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());
const Plugins = (window.Capacitor && window.Capacitor.Plugins) || {};

// ---------------------------------------------------------------- which phone am I?
// In the app the role is saved on the phone. For browser testing use ?role=mum|son|daughter.
const params = new URLSearchParams(location.search);
const CFG_KEY = 'fv-cfg';
const cfg = (() => { try { return JSON.parse(localStorage.getItem(CFG_KEY) || '{}'); } catch { return {}; } })();
const roleFromUrl = params.get('role');
const ROLE = PEOPLE[roleFromUrl] ? roleFromUrl : (PEOPLE[cfg.role] ? cfg.role : null);
function defaultRelay() {
  if (params.get('relay')) return params.get('relay');
  if (location.port) return `ws://${location.host}`;            // served by the relay itself (browser testing)
  return cfg.relay || 'ws://192.168.137.1:8000';                // Windows Mobile Hotspot address of the laptop
}
const saveCfg = () => { if (!roleFromUrl) localStorage.setItem(CFG_KEY, JSON.stringify(cfg)); };
const NS = (k) => `fv-${ROLE}-${k}`; // per-role storage (browser tabs share localStorage)

// ---------------------------------------------------------------- persistent settings (Mum's phone)
const defaults = () => ({
  descriptors: { son: [], daughter: [] },
  guardians: { son: true, daughter: true },
  primary: 'daughter',
  elderConsent: true,
  elderMode: true,
  secretQuestion: "What did we eat after Grandma's 70th birthday?",
  replyTimeout: 15,
  callerId: 'unknown',
  matchThreshold: 0.5,
  blurThreshold: 40,
  showDebug: true,
  notifyOnBlur: false,
  camFacing: 'user',
});
let store = loadStore();
function loadStore() {
  let s;
  try { s = { ...defaults(), ...JSON.parse(localStorage.getItem(NS('store')) || '{}') }; } catch { s = defaults(); }
  for (const id of IDS) s.descriptors[id] ||= [];
  return s;
}
const save = () => localStorage.setItem(NS('store'), JSON.stringify(store));

// member phones keep what Mum told them about their role
let memberCfg = { guardian: true, primary: false, elderMode: true };

const rt = { modelsReady: false, stream: null, streamFacing: null, enrolling: false, force: 'auto' };

// ---------------------------------------------------------------- log (local + forwarded to the laptop monitor)
function log(tag, html) {
  const li = document.createElement('li');
  li.innerHTML = `<time>${hhmmss()}</time><span class="tag tag-${tag}">${tag}</span><span class="msg">${html}</span>`;
  const ol = $('#log');
  ol.prepend(li);
  while (ol.children.length > 150) ol.lastChild.remove();
  net.raw({ t: 'log', tag, html });
}
function logSep(text) {
  const li = document.createElement('li');
  li.className = 'sep';
  li.textContent = `── ${text} ──`;
  $('#log').prepend(li);
  net.raw({ t: 'log', tag: 'sep', html: esc(text) });
}

// ---------------------------------------------------------------- crypto: this device's key + pinned family keys
const enc = new TextEncoder();
const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const ECDSA = { name: 'ECDSA', hash: 'SHA-256' };
const EC = { name: 'ECDSA', namedCurve: 'P-256' };
const myKey = { privateKey: null, publicKey: null, jwk: null, fp: '' };
const peerKeys = {};   // role -> CryptoKey (verified + pinned)
let pins = {};         // role -> fingerprint

async function fingerprint(jwk) {
  const h = await crypto.subtle.digest('SHA-256', enc.encode(`${jwk.x}.${jwk.y}`));
  return [...new Uint8Array(h)].slice(0, 6).map((b) => b.toString(16).padStart(2, '0')).join(':');
}
async function initKeys() {
  const saved = localStorage.getItem(NS('key'));
  if (saved) {
    const { priv, pub } = JSON.parse(saved);
    myKey.privateKey = await crypto.subtle.importKey('jwk', priv, EC, false, ['sign']);
    myKey.jwk = pub;
  } else {
    const k = await crypto.subtle.generateKey(EC, true, ['sign', 'verify']);
    const priv = await crypto.subtle.exportKey('jwk', k.privateKey);
    const pub = await crypto.subtle.exportKey('jwk', k.publicKey);
    localStorage.setItem(NS('key'), JSON.stringify({ priv, pub }));
    myKey.privateKey = k.privateKey;
    myKey.jwk = pub;
  }
  myKey.fp = await fingerprint(myKey.jwk);
  try { pins = JSON.parse(localStorage.getItem(NS('pins')) || '{}'); } catch { pins = {}; }
  renderKeyInfo();
}
async function sign(payload) {
  const body = JSON.stringify(payload);
  const sig = await crypto.subtle.sign(ECDSA, myKey.privateKey, enc.encode(body));
  return { body, sig: b64(sig) };
}
async function verifyFrom(env, role) {
  const key = peerKeys[role];
  if (!key) return false;
  try { return await crypto.subtle.verify(ECDSA, key, unb64(env.sig), enc.encode(env.body)); } catch { return false; }
}
const keyMismatch = {};
async function learnPeerKey(role, jwk) {
  if (!jwk || role === ROLE) return;
  const fp = await fingerprint(jwk);
  if (pins[role] && pins[role] !== fp) {
    if (!keyMismatch[role]) log('attack', `✗ A device claiming to be <b>${nm(role)}</b> has a different key (${code(fp)}) from the one paired at setup (${code(pins[role])}). Not trusted. If ${nm(role)} reinstalled the app, tap "Forget paired family keys".`);
    keyMismatch[role] = fp;
    delete peerKeys[role];
    return;
  }
  delete keyMismatch[role];
  if (!pins[role]) {
    pins[role] = fp;
    localStorage.setItem(NS('pins'), JSON.stringify(pins));
    log('sec', `Paired with ${nm(role)}'s device. Key fingerprint ${code(fp)} pinned on this phone.`);
  }
  peerKeys[role] = await crypto.subtle.importKey('jwk', jwk, EC, false, ['verify']);
  renderKeyInfo();
}
function renderKeyInfo() {
  const lines = [`This phone (${nm(ROLE)}): ${code(myKey.fp)}`];
  for (const r of Object.keys(PEOPLE)) {
    if (r === ROLE) continue;
    lines.push(`${nm(r)}: ${pins[r] ? code(pins[r]) : '<i>not paired yet</i>'}${keyMismatch[r] ? ' ⚠️ <b>key mismatch</b>' : ''}`);
  }
  $('#key-info').innerHTML = `Device keys (ECDSA P-256):<br>${lines.join('<br>')}`;
}

// ---------------------------------------------------------------- network (WebSocket relay = push stand-in)
const net = {
  ws: null, ok: false, url: '', peers: {}, timer: null,
  raw(obj) { if (this.ok) { try { this.ws.send(JSON.stringify(obj)); } catch { /* ignore */ } } },
};
const isOnline = (r) => !!net.peers[r]?.online;

function connect() {
  clearTimeout(net.timer);
  if (net.ws) { net.ws.onclose = null; try { net.ws.close(); } catch { /* ignore */ } }
  net.url = `${cfg.relay.replace(/\/+$/, '')}/ws`;
  let ws;
  try { ws = new WebSocket(net.url); } catch (e) { setNetStatus(false, e.message); net.timer = setTimeout(connect, 2000); return; }
  net.ws = ws;
  ws.onopen = () => {
    net.ok = true;
    ws.send(JSON.stringify({ t: 'hello', role: ROLE, pub: myKey.jwk, name: nm(ROLE) }));
    setNetStatus(true);
  };
  ws.onmessage = (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (m.t === 'peers') onPeers(m.peers);
    else if (m.t === 'msg') onMessage(m.from, m.body || {});
    else if (m.t === 'undeliverable') log('warn', `✗ ${nm(m.to)}'s phone is not connected. "${esc(m.kind)}" was not delivered.`);
  };
  ws.onclose = (ev) => {
    net.ok = false;
    setNetStatus(false, ev.code === 4000 ? 'another phone took this role' : '');
    net.timer = setTimeout(connect, 1500);
  };
  ws.onerror = () => {};
}
function setNetStatus(ok, why = '') {
  const pill = $('#net-status');
  pill.className = `pill ${ok ? 'pill-ok' : 'pill-err'}`;
  pill.textContent = ok ? `Connected · ${cfg.relay.replace(/^wss?:\/\//, '')}` : `Not connected${why ? ` (${why})` : ''}`;
  if (ROLE !== 'mum') $('.offline-mask', $('#phone-member')).hidden = ok;
  renderFamilyStatus();
}

async function onPeers(peers) {
  const before = net.peers;
  net.peers = peers;
  for (const [r, p] of Object.entries(peers)) {
    if (r === ROLE) continue;
    if (p.pub) await learnPeerKey(r, p.pub);
    const was = !!before[r]?.online;
    if (was !== p.online && Object.keys(before).length) log('info', `${PEOPLE[r].emoji} ${nm(r)}'s phone is now ${p.online ? 'online' : '<b>OFFLINE</b>'}.`);
  }
  if (ROLE === 'mum') sendConfig();
  renderFamilyStatus();
  if (ROLE === 'mum') renderMumHome();
}

// send to another phone. Returns false if that phone isn't connected (like a phone with no signal).
function sendTo(to, body, label = 'message') {
  if (!net.ok) { log('warn', `✗ This phone is not connected to the relay. ${esc(label)} not sent.`); return false; }
  if (!isOnline(to)) { log('warn', `✗ ${nm(to)}'s phone is offline. ${esc(label)} not delivered.`); return false; }
  net.raw({ t: 'msg', to, body });
  return true;
}

function onMessage(from, body) {
  // presenter commands from the laptop monitor (demo only)
  if (body.kind === 'demo') return onDemoCommand(body.action);
  if (ROLE === 'mum') {
    if (body.kind === 'reply') return receiveReply(from, body.env);
    if (body.kind === 'guardian-call') return onGuardianCall(from, body.callId);
    if (body.kind === 'guardian-warn') return onGuardianWarn(body.env);
    if (body.kind === 'face') return onFaceShared(body.env);
  } else {
    if (body.kind === 'ping') return phone.showPing(body.req);
    if (body.kind === 'expire-ping') return phone.expirePing(body.nonce);
    if (body.kind === 'cancel-ping') return phone.cancelPing(body.nonce);
    if (body.kind === 'alert') return phone.receiveAlert(body.env);
    if (body.kind === 'shared') return phone.addShared(body.callId, body.text);
    if (body.kind === 'config') return phone.applyConfig(body);
  }
}

function onDemoCommand(action) {
  if (action === 'reset') return ROLE === 'mum' ? resetDemo() : phone.clear();
  if (ROLE !== 'mum') return;
  if (action === 'call') startCall();
  else if (action === 'end') endCall('Call ended by presenter');
  else if (action === 'expire') skipToNoReply();
  else if (action === 'elder-on' || action === 'elder-off') setStore({ elderMode: action === 'elder-on' && store.elderConsent });
}

// ---------------------------------------------------------------- phone feel: vibration + notifications
const Haptics = Plugins.Haptics;
const LocalNotifications = Plugins.LocalNotifications;
function vibrate(pattern) {
  if (Haptics && isNative) {
    (Array.isArray(pattern) ? pattern.filter((_, i) => i % 2 === 0) : [pattern]).forEach((d, i) => {
      setTimeout(() => Haptics.vibrate({ duration: d }).catch(() => {}), i * (d + 120));
    });
  } else if (navigator.vibrate) navigator.vibrate(pattern);
}
let notifId = 1;
async function osNotify(title, body, { urgent = false, actions = null } = {}) {
  if (!LocalNotifications || !isNative) return;
  try {
    await LocalNotifications.schedule({
      notifications: [{
        id: notifId++, title, body,
        channelId: urgent ? 'fv-urgent' : 'fv-normal',
        actionTypeId: actions || undefined,
        extra: null,
      }],
    });
  } catch (e) { console.warn('notify failed', e); }
}
async function setupNative() {
  if (!isNative) return;
  try {
    if (LocalNotifications) {
      const p = await LocalNotifications.checkPermissions();
      if (p.display !== 'granted') await LocalNotifications.requestPermissions();
      await LocalNotifications.createChannel({ id: 'fv-urgent', name: 'Urgent family alerts', importance: 5, vibration: true, visibility: 1 });
      await LocalNotifications.createChannel({ id: 'fv-normal', name: 'Family Verify', importance: 4, vibration: true, visibility: 1 });
      // tapping a notification just brings the app forward; the answer happens in-app (with fingerprint)
    }
  } catch (e) { console.warn('native setup', e); }
}
// fingerprint / face unlock before answering (falls back to a simulated prompt in the browser)
async function biometricConfirm(reason) {
  const B = Plugins.BiometricAuthNative;
  if (isNative && B) {
    try {
      const info = await B.checkBiometry();
      if (info.isAvailable || info.deviceIsSecure) {
        await B.internalAuthenticate({
          reason, cancelTitle: 'Cancel', androidTitle: 'Family Verify', androidSubtitle: reason,
          allowDeviceCredential: true, androidConfirmationRequired: false,
        });
        return true;
      }
    } catch (e) {
      log('warn', `Fingerprint check cancelled or failed (${esc(e.message || e.code || e)}).`);
      return false;
    }
  }
  return null; // not available -> caller shows the simulated prompt
}

// ---------------------------------------------------------------- face models + camera
const tinyOpts = () => new faceapi.TinyFaceDetectorOptions({ inputSize: 320, scoreThreshold: 0.45 });
const ssdOpts = () => new faceapi.SsdMobilenetv1Options({ minConfidence: 0.5 });

async function loadModels() {
  const pill = $('#model-status');
  try {
    try { await faceapi.tf.setBackend('webgl'); } catch { /* falls back automatically */ }
    await faceapi.tf.ready();
    await Promise.all([
      faceapi.nets.tinyFaceDetector.loadFromUri(MODEL_URL),
      faceapi.nets.ssdMobilenetv1.loadFromUri(MODEL_URL),
      faceapi.nets.faceLandmark68Net.loadFromUri(MODEL_URL),
      faceapi.nets.faceRecognitionNet.loadFromUri(MODEL_URL),
    ]);
    const c = Object.assign(document.createElement('canvas'), { width: 160, height: 160 });
    await faceapi.detectSingleFace(c, tinyOpts()).withFaceLandmarks().withFaceDescriptor();
    rt.modelsReady = true;
    pill.className = 'pill pill-ok';
    pill.textContent = `Face models ready (${faceapi.tf.getBackend()})`;
    log('face', `Face models loaded on ${nm(ROLE)}'s phone (${faceapi.tf.getBackend()}). Nothing is sent to a server.`);
  } catch (e) {
    console.error(e);
    pill.className = 'pill pill-err';
    pill.textContent = 'Face models failed to load';
    log('warn', `Model load failed: ${esc(e.message)}. Use the forced-face buttons.`);
  }
}

async function ensureCamera(facing = store.camFacing) {
  if (rt.stream && rt.streamFacing === facing) return rt.stream;
  if (rt.stream) rt.stream.getTracks().forEach((t) => t.stop());
  rt.stream = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: facing }, audio: false });
  rt.streamFacing = facing;
  return rt.stream;
}
async function attachCamera(videoEl, facing) {
  videoEl.srcObject = await ensureCamera(facing);
  videoEl.classList.toggle('no-mirror', rt.streamFacing === 'environment');
  await videoEl.play().catch(() => {});
  if (videoEl.readyState < 2) await new Promise((r) => videoEl.addEventListener('loadeddata', r, { once: true }));
}
function stopCameraIfIdle() {
  if (call.active || rt.enrolling || !rt.stream) return;
  rt.stream.getTracks().forEach((t) => t.stop());
  rt.stream = null;
  rt.streamFacing = null;
  $('#cam').srcObject = null;
  $('#enrol-video').srcObject = null;
}

function bestMatch(desc) {
  let best = { id: null, distance: Infinity };
  for (const id of IDS) {
    for (const d of store.descriptors[id]) {
      const dist = faceapi.euclideanDistance(desc, d);
      if (dist < best.distance) best = { id, distance: dist };
    }
  }
  return best;
}

// Blur check: variance of the Laplacian on the face crop (low = blurry)
const sharpCanvas = Object.assign(document.createElement('canvas'), { width: 128, height: 128 });
const sctx = sharpCanvas.getContext('2d', { willReadFrequently: true });
function sharpness(src, box) {
  const W = src.videoWidth || src.width, H = src.videoHeight || src.height;
  const x = Math.max(0, box.x), y = Math.max(0, box.y);
  const w = Math.min(W - x, box.width), h = Math.min(H - y, box.height);
  if (w < 8 || h < 8) return 0;
  sctx.drawImage(src, x, y, w, h, 0, 0, 128, 128);
  const d = sctx.getImageData(0, 0, 128, 128).data;
  const g = new Float32Array(128 * 128);
  for (let i = 0; i < g.length; i++) g[i] = 0.299 * d[i * 4] + 0.587 * d[i * 4 + 1] + 0.114 * d[i * 4 + 2];
  let sum = 0, sum2 = 0, n = 0;
  for (let yy = 1; yy < 127; yy++) {
    for (let xx = 1; xx < 127; xx++) {
      const i = yy * 128 + xx;
      const l = g[i - 128] + g[i + 128] + g[i - 1] + g[i + 1] - 4 * g[i];
      sum += l; sum2 += l * l; n++;
    }
  }
  const mean = sum / n;
  return sum2 / n - mean * mean;
}

// ---------------------------------------------------------------- enrolment
const embPreview = (desc) => '[' + Array.from(desc).slice(0, 4).map((x) => x.toFixed(3)).join(', ') + ', … 124 more]';
const roundDesc = (desc) => Array.from(desc, (x) => +x.toFixed(5));
function addDescriptor(id, desc, source) {
  store.descriptors[id].push(roundDesc(desc));
  save();
  renderEnrol();
  renderMumHome();
  log('face', `Enrolled <b>${nm(id)}</b> from ${esc(source)}. Stored embedding ${code(embPreview(desc))}, image discarded.`);
}

function loadImage(file) {
  return new Promise((res, rej) => {
    const img = new Image();
    img.onload = () => res(img);
    img.onerror = () => rej(new Error('Could not read image'));
    img.src = URL.createObjectURL(file);
  });
}
const enrolMsg = (t) => { $('#enrol-msg').textContent = t; };

async function enrolFromFiles(id, files) {
  if (!rt.modelsReady) return enrolMsg('Face models are still loading…');
  for (const f of files) {
    enrolMsg(`Reading ${f.name}…`);
    try {
      const img = await loadImage(f);
      const det = await faceapi.detectSingleFace(img, ssdOpts()).withFaceLandmarks().withFaceDescriptor();
      URL.revokeObjectURL(img.src);
      if (!det) { enrolMsg(`⚠️ No face found in ${f.name}. Try a clear, front-facing photo.`); continue; }
      addDescriptor(id, det.descriptor, `photo "${f.name}"`);
      enrolMsg(`✓ ${nm(id)} enrolled from ${f.name}`);
    } catch (e) { enrolMsg(`⚠️ ${e.message}`); }
  }
}

// grabs up to `want` face embeddings from the camera (used by Mum's enrolment and "share my face")
async function captureSamples(label, want, facing, onMsg) {
  rt.enrolling = true;
  const wrap = $('#enrol-cam'), v = $('#enrol-video'), msg = $('#enrol-cam-msg');
  wrap.hidden = false;
  const out = [];
  try {
    await attachCamera(v, facing);
    for (let i = 0; i < 20 && out.length < want; i++) {
      msg.textContent = `${label}: look at the camera… (${out.length}/${want})`;
      await sleep(400);
      const det = await faceapi.detectSingleFace(v, ssdOpts()).withFaceLandmarks().withFaceDescriptor();
      if (det) out.push(det.descriptor);
    }
  } catch (e) {
    onMsg(`⚠️ Camera error: ${e.message}`);
  } finally {
    rt.enrolling = false;
    wrap.hidden = true;
    stopCameraIfIdle();
  }
  return out;
}
async function enrolFromCamera(id) {
  if (!rt.modelsReady) return enrolMsg('Face models are still loading…');
  if (rt.enrolling) return;
  closeDrawer();
  const got = await captureSamples(nm(id), 3, store.camFacing, enrolMsg);
  got.forEach((d, i) => addDescriptor(id, d, `camera sample ${i + 1}`));
  enrolMsg(got.length ? `✓ Captured ${got.length} sample(s) for ${nm(id)}` : '⚠️ No face seen. Check lighting and try again.');
  openDrawer();
}

function renderEnrol() {
  for (const row of $$('.enrol-row')) {
    const n = store.descriptors[row.dataset.id].length;
    const el = $('.enrol-count', row);
    el.textContent = n ? `✓ ${n} face sample${n > 1 ? 's' : ''} (embeddings only)` : 'Not enrolled';
    el.classList.toggle('ok', n > 0);
  }
}

// a family member sent their own face embeddings, signed by their device
async function onFaceShared(env) {
  let p;
  try { p = JSON.parse(env.body); } catch { return; }
  if (!IDS.includes(p.from) || !(await verifyFrom(env, p.from))) {
    log('attack', `✗ Rejected face data claiming to be from ${esc(nm(p.from))}: bad signature.`);
    return;
  }
  const descs = (p.descriptors || []).filter((d) => Array.isArray(d) && d.length === 128);
  store.descriptors[p.from] = descs;
  save();
  renderEnrol();
  renderMumHome();
  log('face', `✓ ${nm(p.from)} shared ${descs.length} face embedding(s) from their own phone (signed). Replaced old samples. No photo was sent.`);
  showMumToast(`${PEOPLE[p.from].emoji} ${nm(p.from)} shared their face`, `${descs.length} sample(s) saved on this phone.`);
}

// ================================================================ THE CALL (Mum's device)
const call = {
  active: false, id: null, status: 'idle', answered: false, startedAt: 0,
  target: null, req: null, timer: null, cdTimer: null,
  streak: { key: null, since: 0, count: 0 }, lastObs: null,
  secretOpen: false, warn: null, blurNotified: false, notified: [], source: 'camera',
};
const LOCKED = new Set(['verifying', 'A', 'B', 'C', 'secret-ok', 'secret-fail', 'warned']);
let callGen = 0;

const callerIdText = () => (call.source === 'whatsapp'
  ? 'WhatsApp video call'
  : store.callerId === 'spoofed' ? '👦 Son' : '+60 11-2345 6789');

async function startCall(source = 'camera') {
  if (call.active || ROLE !== 'mum') return;
  closeDrawer();
  $('#mum-ended').hidden = true;
  Object.assign(call, {
    active: true, id: randHex(4), status: 'scanning', answered: source === 'whatsapp', startedAt: now(),
    target: null, req: null, streak: { key: null, since: 0, count: 0 }, lastObs: null,
    secretOpen: false, warn: null, blurNotified: false, notified: [], source,
  });
  const gen = ++callGen;
  $('#mum-home').hidden = true;
  $('#mum-call').hidden = false;
  renderMum();
  logSep(`New call ${call.id}`);
  log('info', `📞 Incoming video call to Mum from <b>${esc(callerIdText())}</b>${store.callerId === 'spoofed' && source !== 'whatsapp' ? ' (caller ID is spoofed; we never trust it)' : ''}. Mode: <b>${store.elderMode ? 'Elder' : 'Standard'}</b>`);
  if (source === 'camera') {
    try {
      await attachCamera($('#cam'));
      $('#no-cam').hidden = true;
    } catch (e) {
      $('#no-cam').hidden = false;
      log('warn', `Camera unavailable (${esc(e.message)}). Use the "Caller's face" buttons.`);
    }
    scanLoop(gen);
  }
}

async function scanLoop(gen) {
  while (call.active && gen === callGen) {
    const t0 = now();
    let obs;
    try { obs = await observe(); } catch (e) { console.error(e); obs = { kind: 'noface' }; }
    if (!call.active || gen !== callGen) break;
    handleObs(obs);
    drawOverlay(obs);
    updateCallSub();
    await sleep(Math.max(50, 220 - (now() - t0)));
  }
}

function forcedObs() {
  return rt.force === 'son' || rt.force === 'daughter'
    ? { kind: 'match', id: rt.force, distance: 0.31, forced: true }
    : { kind: rt.force, forced: true };
}

// analyse one image source (video element or canvas) -> observation
async function analyse(src) {
  const det = await faceapi.detectSingleFace(src, tinyOpts()).withFaceLandmarks().withFaceDescriptor();
  if (!det) return { kind: 'noface' };
  const box = det.detection.box;
  const sharp = sharpness(src, box);
  if (sharp < store.blurThreshold) return { kind: 'blurry', box, sharp };
  const m = bestMatch(det.descriptor);
  if (m.id && m.distance < store.matchThreshold) return { kind: 'match', id: m.id, distance: m.distance, box, sharp };
  return { kind: 'unknown', distance: m.distance, box, sharp };
}

async function observe() {
  if (rt.force !== 'auto') return forcedObs();
  const v = $('#cam');
  if (!rt.modelsReady) return { kind: 'noface', why: 'models loading' };
  if (!v.srcObject || v.readyState < 2) return { kind: 'noface', why: 'no video' };
  return analyse(v);
}

function handleObs(obs) {
  call.lastObs = obs;
  renderDebug(obs);
  if (LOCKED.has(call.status)) return;
  const key = obs.kind === 'match' ? `match:${obs.id}` : obs.kind === 'unknown' ? 'unknown' : 'unclear';
  const s = call.streak;
  if (s.key === key) s.count++;
  else Object.assign(s, { key, since: now(), count: 1 });
  const needed = call.source === 'whatsapp' ? 2 : 3;

  if (obs.kind === 'match' && s.count >= needed) return startVerification(obs.id, obs);             // A/B/C
  if (key === 'unclear' && now() - s.since > 2500 && call.status !== 'E') return setStatus('E');   // E
  if (key === 'unknown' && now() - s.since > 1500 && call.status !== 'D') return setStatus('D');   // D
}

function setStatus(st) {
  call.status = st;
  const msgs = {
    D: '⚪ State D: face is not an enrolled family member. Normal call, no shield.',
    E: '🟠 State E: can\'t see the caller\'s face clearly (blurry, covered or no face).',
  };
  if (msgs[st]) log('face', msgs[st]);
  if (st === 'E' && store.elderMode && store.notifyOnBlur && !call.blurNotified) {
    call.blurNotified = true;
    alertGuardians({ level: 'info', title: 'Mum is on a video call where the caller\'s face can\'t be seen clearly', body: 'No action needed unless she asks for help.' });
  }
  renderMum();
}

// ---- step 3: out-of-band ping to the enrolled person's real phone
function startVerification(id, obs) {
  const req = {
    type: 'verify-request', callId: call.id, to: id, nonce: randHex(16),
    prompt: 'Are you calling Mum right now?', ts: now(), expires: now() + store.replyTimeout * 1000,
    elder: store.elderMode,
  };
  call.status = 'verifying';
  call.target = id;
  call.req = req;
  log('face', `Face matches enrolled <b>${nm(id)}</b>${obs.forced ? ' [forced]' : ` (distance ${obs.distance.toFixed(2)} &lt; ${store.matchThreshold.toFixed(2)})`}. A deepfake would match too, so now we ask the real ${nm(id)}.`);
  log('push', `Mum → ${nm(id)}'s phone: ${code({ nonce: req.nonce, prompt: req.prompt, expires: `${store.replyTimeout}s` })}. No video, audio or location.`);
  sendTo(id, { kind: 'ping', req }, 'Verification request');

  call.timer = setTimeout(() => onReply(id, req.nonce, 'timeout'), store.replyTimeout * 1000);
  call.cdTimer = setInterval(updateCountdown, 250);
  if (store.elderMode) {
    alertGuardians({
      level: 'info', about: id,
      title: `Mum is getting a video call from someone who looks like ${nm(id)}`,
      body: `Checking with ${nm(id)}'s phone now…`,
    });
  }
  renderMum();
  mumNotify();
}

async function receiveReply(relayFrom, env) {
  let p;
  try { p = JSON.parse(env.body); } catch { return; }
  const from = p.from;
  if (!IDS.includes(from) || !(await verifyFrom(env, from))) {
    log('attack', `✗ Mum's phone rejected a reply claiming to be from ${esc(nm(from))}: bad signature`);
    return;
  }
  log('sec', `Mum's phone: ✓ reply signature from ${nm(from)}'s device verified`);
  onReply(from, p.nonce, p.answer);
}

function onReply(id, nonce, answer) {
  const req = call.req;
  if (!call.active || !req || req.nonce !== nonce || call.status !== 'verifying') {
    if (answer !== 'timeout') log('attack', `✗ Ignored reply from ${nm(id)}: nonce ${code(String(nonce).slice(0, 8) + '…')} doesn't match an open request (stale or replayed)`);
    return;
  }
  clearTimeout(call.timer);
  clearInterval(call.cdTimer);
  call.req = null; // nonce is single-use
  const who = nm(id);

  if (answer === 'yes') {
    call.status = 'A';
    log('info', `🟢 State A: ${who} confirmed on their own device. Call cleared.`);
    if (store.elderMode) alertGuardians({ level: 'log', about: id, title: `Mum took a verified call from ${who}` });
  } else if (answer === 'no') {
    call.status = 'B';
    log('info', `🔴 State B: ${who} says it is NOT them. Spoof detected.`);
    if (store.elderMode) {
      alertGuardians({
        level: 'urgent', about: id,
        title: `🚨 Someone pretending to be ${who} is video-calling Mum`,
        body: `${who} confirmed it's NOT them. Mum has been told to hang up.`,
      });
    }
  } else {
    call.status = 'C';
    call.secretOpen = true;
    sendTo(id, { kind: 'expire-ping', nonce }, 'Expiry notice');
    log('info', `🟠 State C: no reply from ${who} in ${store.replyTimeout}s. Can't verify.`);
    if (store.elderMode) {
      alertGuardians({
        level: 'urgent', about: id,
        title: `⚠️ Couldn't verify a caller who looks like ${who}`,
        body: `${who} didn't reply. Please check on Mum.`,
      });
    }
  }
  renderMum();
  mumNotify();
}

function skipToNoReply() {
  if (call.status === 'verifying' && call.req) onReply(call.target, call.req.nonce, 'timeout');
  else log('warn', 'Nothing is waiting for a reply right now.');
}

function secretAnswer(ok) {
  if (!call.active) return;
  call.status = ok ? 'secret-ok' : 'secret-fail';
  log('info', ok ? '🟢 Caller answered the family secret question.' : '🔴 Caller could NOT answer the secret question. No answer, no money.');
  if (!ok && store.elderMode) {
    alertGuardians({
      level: 'urgent', about: call.target,
      title: '🚨 Caller failed Mum\'s family secret question',
      body: 'Mum has been told to hang up. Please call her.',
    });
  }
  renderMum();
}

function answerCall() {
  if (!call.active || call.answered) return;
  call.answered = true;
  log('info', 'Mum answered. Verification keeps running.');
  renderMum();
}

function endCall(reason = 'Call ended') {
  if (!call.active) return;
  call.active = false;
  callGen++;
  clearTimeout(call.timer);
  clearInterval(call.cdTimer);
  if (call.req) sendTo(call.target, { kind: 'cancel-ping', nonce: call.req.nonce }, 'Cancel notice');
  call.req = null;
  call.status = 'idle';
  log('info', `📴 ${esc(reason)}`);
  $('#mum-full').hidden = true;
  $('#mum-toast').hidden = true;
  $('#mum-ended').hidden = false;
  setTimeout(() => {
    if (call.active) return;
    $('#mum-ended').hidden = true;
    $('#mum-call').hidden = true;
    $('#mum-home').hidden = false;
    renderMumHome();
  }, 1400);
  stopCameraIfIdle();
  if (window.onCallEnded) window.onCallEnded();
}

// ---------------------------------------------------------------- guardian alerts (signed by Mum's device)
async function alertGuardians({ level, title, body = '', about = null }) {
  if (!store.elderMode) return;
  let to = IDS.filter((g) => store.guardians[g] && g !== about);
  if (!to.length) to = IDS.filter((g) => store.guardians[g]);
  if (!to.length) { log('warn', 'Mum has not chosen any guardians, so no one was alerted.'); return; }
  const payload = { type: 'guardian-alert', level, title, body, about, callId: call.id, from: 'mum', ts: now(), nonce: randHex(12) };
  const env = await sign(payload);
  call.notified = [...new Set([...call.notified, ...to])];
  const label = { urgent: '🚨 URGENT alert', info: 'Heads-up', log: 'Silent log' }[level];
  log('sec', `${label} signed by Mum's device → ${to.map(nm).join(' + ')} at the same time ${code({ level, nonce: payload.nonce, sig: env.sig.slice(0, 18) + '…' })}`);
  for (const g of to) sendTo(g, { kind: 'alert', env }, label);
  if (level === 'urgent') renderMum();
}

function sendConfig() {
  for (const id of IDS) {
    if (!isOnline(id)) continue;
    net.raw({ t: 'msg', to: id, body: { kind: 'config', guardian: !!store.guardians[id], primary: store.primary === id, elderMode: store.elderMode } });
  }
}

// guardian actions arriving at Mum
function onGuardianCall(gid, callId) {
  if (!IDS.includes(gid)) return;
  log('push', `${nm(gid)} → Mum: incoming voice call`);
  vibrate([300, 150, 300]);
  showMumToast(
    `📞 ${nm(gid)} is calling you`,
    call.active ? 'Your family is calling. Switch to them?' : '',
    call.active ? [{ label: `Talk to ${nm(gid)}`, cls: 'b-green', act: () => endCall(`Mum hung up and switched to ${nm(gid)}'s call`) }] : [],
  );
  osNotify(`📞 ${nm(gid)} is calling you`, 'Your family is calling.', { urgent: true });
}
async function onGuardianWarn(env) {
  let p;
  try { p = JSON.parse(env.body); } catch { return; }
  const gid = p.from;
  if (!IDS.includes(gid) || !(await verifyFrom(env, gid))) {
    log('attack', `✗ Mum's phone rejected a "hang up" message claiming to be from ${esc(nm(gid))}: bad signature`);
    return;
  }
  log('sec', `Mum's phone: ✓ warning signed by ${nm(gid)}'s device`);
  if (!call.active || call.id !== p.callId) return;
  call.status = 'warned';
  call.warn = { from: gid, text: p.text };
  clearTimeout(call.timer);
  clearInterval(call.cdTimer);
  renderMum();
  mumNotify();
}

// ---------------------------------------------------------------- Mum rendering
const guardiansList = () => {
  const gs = IDS.filter((g) => store.guardians[g]);
  gs.sort((a, b) => (b === store.primary) - (a === store.primary));
  return gs;
};
const notifiedText = () => (call.notified.length ? `${call.notified.map(nm).join(' and ')} ${call.notified.length > 1 ? 'have' : 'has'} been told.` : '');

function renderMumHome() {
  if (ROLE !== 'mum') return;
  const gs = guardiansList();
  $('#mum-home-info').innerHTML = `
    ${store.elderMode ? '<b>Elder Mode on</b><br>' : 'Standard Mode<br>'}
    Guardians: ${gs.length ? gs.map((g) => `${MEMBERS[g].emoji} ${nm(g)}${g === store.primary ? ' ★' : ''}`).join(', ') : 'none chosen'}<br>
    Faces enrolled: ${IDS.filter((id) => store.descriptors[id].length).map(nm).join(', ') || 'none yet (open ⚙)'}`;
}
function renderFamilyStatus() {
  const dot = (r) => {
    const on = r === ROLE ? net.ok : isOnline(r);
    return `<span class="fam ${on ? 'on' : 'off'}">${PEOPLE[r].emoji} ${nm(r)}${r === ROLE ? ' (me)' : ''}</span>`;
  };
  const html = Object.keys(PEOPLE).map(dot).join('');
  const el = ROLE === 'mum' ? $('#family-status') : $('#member-status');
  if (el) el.innerHTML = html;
}

function updateCallSub() {
  const secs = Math.floor((now() - call.startedAt) / 1000);
  const t = `${String(Math.floor(secs / 60)).padStart(2, '0')}:${String(secs % 60).padStart(2, '0')}`;
  $('#call-sub').textContent = call.answered ? `Video call · ${t}` : `Incoming video call… ${store.callerId === 'spoofed' ? '' : '(unknown number)'}`;
}
function updateCountdown() {
  if (!call.req) return;
  const left = Math.max(0, Math.ceil((call.req.expires - now()) / 1000));
  $$('.cd').forEach((el) => { el.textContent = left; });
}

function renderMum() {
  if (ROLE !== 'mum') return;
  const elder = store.elderMode;
  const phoneEl = $('#phone-mum');
  phoneEl.classList.toggle('elder', elder);
  if (!call.active) { renderMumHome(); return; }

  $('#caller-id').textContent = callerIdText();
  $('#btn-answer').hidden = call.answered;
  updateCallSub();

  const who = call.target ? nm(call.target) : '';
  const q = esc(store.secretQuestion);
  const told = notifiedText();
  const secretBlock = `
    <div class="secret">
      <div>Ask the caller:</div>
      <div class="q">“${q}”</div>
      <div class="acts">
        <button class="b-green" data-action="secret-ok">✓ Answered right</button>
        <button class="b-red" data-action="secret-fail">✗ Couldn't answer</button>
      </div>
      <div class="rule">Rule: No answer, no money.</div>
    </div>`;

  let banner = '', full = '';
  switch (call.status) {
    case 'scanning':
      banner = `<div class="banner scan"><span class="spinner"></span>${elder ? 'Checking who is calling…' : 'Checking caller…'}</div>`;
      break;
    case 'D':
      banner = `<div class="banner none">${elder ? 'Not a family member. Be careful with money.' : 'No family match · normal call'}</div>`;
      break;
    case 'E':
      banner = `<div class="banner amber"><div class="t">🟠 Can't see the caller clearly.</div>
        <div class="d">Be careful. ${elder ? 'Ask them to show their face.' : 'Don\'t trust a face you can\'t see.'}</div>
        ${call.secretOpen ? secretBlock : `<div class="acts"><button class="b-grey" data-action="secret-open">Ask secret question</button>${elder ? '<button class="b-red" data-action="hangup">Hang up</button>' : ''}</div>`}</div>`;
      break;
    case 'verifying':
      banner = elder
        ? `<div class="banner verify"><div class="t"><span class="spinner"></span>Checking with ${who}…</div><div class="d">Please wait. Don't send anything yet. (<b class="cd"></b>s)</div></div>`
        : `<div class="banner verify"><div class="t"><span class="spinner"></span>Looks like ${who}. Asking ${who}'s phone…</div><div class="d">Waiting for reply · <b class="cd"></b>s left</div></div>`;
      break;
    case 'A':
      banner = elder
        ? `<div class="banner green"><div class="t">🟢 This is really ${who}</div><div class="d">${who} confirmed on their own phone.</div></div>`
        : `<div class="banner green"><div class="t">🟢 Identity confirmed by device</div><div class="d">${who} confirmed on their registered phone.</div></div>`;
      break;
    case 'B':
      if (elder) {
        full = `<div class="fullscreen red"><div class="icon">⛔</div><div class="big">SCAM</div>
          <div class="t">Hang up.<br>Don't send money.</div>
          <div class="d">The real ${who} says this is NOT them.</div>
          ${told ? `<div class="who">${told}</div>` : ''}
          <div class="acts"><button class="b-red" data-action="hangup">HANG UP</button></div></div>`;
      } else {
        banner = `<div class="banner red"><div class="t">🔴 Spoof detected</div><div class="d">They say it's not them. The real ${who} denied this call on their phone.</div>
          <div class="acts"><button class="b-red" data-action="hangup">End call</button></div></div>`;
      }
      break;
    case 'C':
      if (elder) {
        full = `<div class="fullscreen amber"><div class="icon">⚠️</div><div class="t">Couldn't check with ${who}</div>
          <div class="d">Ask this. <b>No answer, no money.</b></div>
          <div class="q">“${q}”</div>
          <div class="acts"><button class="b-green" data-action="secret-ok">✓ Answered right</button>
          <button class="b-red" data-action="secret-fail">✗ Couldn't answer</button>
          <button class="b-red" data-action="hangup">HANG UP</button></div>
          ${told ? `<div class="who">${told}</div>` : ''}</div>`;
      } else {
        banner = `<div class="banner amber"><div class="t">🟠 Unable to verify</div><div class="d">${who} didn't reply. Ask your secret question.</div>${secretBlock}</div>`;
      }
      break;
    case 'secret-ok':
      banner = `<div class="banner green"><div class="t">🟢 Caller answered the family question</div><div class="d">Checked by question, not by device. Still be careful with money.</div></div>`;
      break;
    case 'secret-fail':
      if (elder) {
        full = `<div class="fullscreen red"><div class="icon">⛔</div><div class="big">NO MONEY</div>
          <div class="t">They couldn't answer.<br>Hang up now.</div>
          ${told ? `<div class="who">${told}</div>` : ''}
          <div class="acts"><button class="b-red" data-action="hangup">HANG UP</button></div></div>`;
      } else {
        banner = `<div class="banner red"><div class="t">🔴 No answer, no money</div><div class="d">The caller failed the secret question.</div>
          <div class="acts"><button class="b-red" data-action="hangup">End call</button></div></div>`;
      }
      break;
    case 'warned': {
      const from = call.warn.from;
      full = `<div class="fullscreen red"><div class="icon">${MEMBERS[from].emoji}</div>
        <div class="who">✓ Verified message from ${nm(from)}'s phone</div>
        <div class="t">“${esc(call.warn.text)}”</div>
        <div class="acts"><button class="b-red" data-action="hangup">HANG UP</button></div></div>`;
      break;
    }
  }
  $('#shield').innerHTML = banner;
  const fs = $('#mum-full');
  fs.innerHTML = full;
  fs.hidden = !full;
  updateCountdown();
}

// what Mum's phone does outside the app (vibration + OS notification) for each result
let lastNotified = '';
function mumNotify() {
  const key = `${call.id}:${call.status}`;
  if (key === lastNotified) return;
  lastNotified = key;
  const who = call.target ? nm(call.target) : 'family';
  const n = {
    verifying: [`Checking with ${who}…`, 'Please wait. Don\'t send anything yet.', false, [80]],
    A: [`🟢 This is really ${who}`, `${who} confirmed on their own phone.`, false, [120, 80, 120]],
    B: ['⛔ SCAM. Hang up.', `The real ${who} says this is NOT them. Don't send money.`, true, [600, 200, 600, 200, 600]],
    C: [`⚠️ Couldn't check with ${who}`, `Ask: "${store.secretQuestion}" No answer, no money.`, true, [400, 200, 400]],
    warned: [`✓ Message from ${call.warn ? nm(call.warn.from) : ''}`, call.warn?.text || '', true, [600, 200, 600]],
  }[call.status];
  if (!n) return;
  vibrate(n[3]);
  // over another app (WhatsApp) the in-app screen isn't visible, so use a heads-up notification
  if (document.hidden || call.source === 'whatsapp') osNotify(n[0], n[1], { urgent: n[2] });
  if (window.onMumVerdict) window.onMumVerdict(call.status, n[0], n[1]);
}

function renderDebug(obs) {
  const el = $('#debug');
  el.hidden = !store.showDebug;
  if (!store.showDebug) return;
  const parts = [];
  if (obs.forced) parts.push('FORCED');
  parts.push({ match: '✓ face', unknown: '✓ face', blurry: '~ face', noface: '✗ no face' }[obs.kind]);
  if (obs.kind === 'match') parts.push(`${nm(obs.id)} d=${obs.distance.toFixed(2)}`);
  if (obs.kind === 'unknown' && !obs.forced) parts.push(Number.isFinite(obs.distance) ? `unknown d=${obs.distance.toFixed(2)}` : 'no faces enrolled');
  if (obs.sharp !== undefined) parts.push(`sharp=${obs.sharp.toFixed(0)}`);
  if (obs.why) parts.push(obs.why);
  el.textContent = parts.join(' · ');
}

function drawOverlay(obs) {
  const v = $('#cam'), c = $('#overlay');
  if (!v.videoWidth) return;
  if (c.width !== v.videoWidth) { c.width = v.videoWidth; c.height = v.videoHeight; }
  c.classList.toggle('no-mirror', v.classList.contains('no-mirror'));
  const ctx = c.getContext('2d');
  ctx.clearRect(0, 0, c.width, c.height);
  if (!store.showDebug || !obs.box) return;
  const color = { match: '#22c55e', unknown: '#e5e7eb', blurry: '#f59e0b' }[obs.kind] || '#fff';
  ctx.strokeStyle = color;
  ctx.lineWidth = 4;
  const { x, y, width, height } = obs.box;
  ctx.strokeRect(x, y, width, height);
}

function showMumToast(title, body, actions = []) {
  const t = $('#mum-toast');
  t.innerHTML = `<div class="t">${esc(title)}</div>${body ? `<div>${esc(body)}</div>` : ''}
    <div class="acts">${actions.map((a, i) => `<button class="${a.cls}" data-i="${i}">${esc(a.label)}</button>`).join('')}<button class="b-grey" data-i="x">Dismiss</button></div>`;
  t.hidden = false;
  $$('button', t).forEach((b) => b.addEventListener('click', () => {
    t.hidden = true;
    if (b.dataset.i !== 'x') actions[+b.dataset.i].act();
  }));
  clearTimeout(t._timer);
  t._timer = setTimeout(() => { t.hidden = true; }, 12000);
}

// ================================================================ FAMILY MEMBER PHONE (Son / Daughter)
class MemberPhone {
  constructor(id) {
    this.id = id;
    this.el = $('#phone-member');
    this.list = $('.notifs', this.el);
    this.seenNonces = new Set(JSON.parse(sessionStorage.getItem(NS('seen')) || '[]'));
    this.pings = new Map();   // nonce -> card
    this.alerts = new Map();  // callId -> card
    this.shared = new Map();  // callId -> [text]
  }
  get name() { return nm(this.id); }

  add(el) { this.list.prepend(el); this.list.scrollTop = 0; return el; }
  card(cls, html) {
    const el = document.createElement('div');
    el.className = `card ${cls}`;
    el.innerHTML = html;
    return el;
  }
  buzz(urgent = false) {
    this.el.classList.remove('shake');
    void this.el.offsetWidth;
    this.el.classList.add('shake');
    vibrate(urgent ? [700, 200, 700, 200, 700] : [250, 120, 250]);
    if (urgent) {
      this.el.classList.add('urgent-ring');
      setTimeout(() => this.el.classList.remove('urgent-ring'), 4000);
    }
  }

  // "Are you calling Mum right now?"
  showPing(req) {
    const left = Math.max(0, req.expires - now());
    const el = this.card('ping', `
      <div class="app"><span>🛡 Family Verify</span><span>now</span></div>
      <div class="t">Are you calling Mum right now?</div>
      <div class="d">Someone who looks like you is video-calling Mum.</div>
      <div class="countdown"><i style="animation-duration:${left}ms"></i></div>
      <div class="acts"><button class="b-green" data-a="yes">Yes, it's me</button><button class="b-red" data-a="no">No, not me</button></div>
      <div class="foot">🔒 Fingerprint required · request ${esc(req.nonce.slice(0, 8))}…</div>`);
    this.pings.set(req.nonce, el);
    $$('button', el).forEach((b) => b.addEventListener('click', () => this.answer(req, b.dataset.a, el)));
    this.add(el);
    this.buzz();
    osNotify('Are you calling Mum right now?', 'Someone who looks like you is video-calling Mum. Open to answer.', { urgent: true });
    log('push', `${this.name}'s phone received the verification request`);
  }

  async answer(req, answer, el) {
    $$('button', el).forEach((b) => { b.disabled = true; });
    // biometric unlock: a stolen phone can't answer
    const real = await biometricConfirm(answer === 'yes' ? 'Confirm it is you calling Mum' : 'Confirm it is NOT you');
    if (real === false) { $$('button', el).forEach((b) => { b.disabled = false; }); return; }
    if (real === null) await this.fakeBio();
    else log('sec', `${this.name}'s phone: ✓ fingerprint verified by Android`);

    const env = await sign({ type: 'verify-reply', nonce: req.nonce, answer, from: this.id, ts: now() });
    log('push', `${this.name}'s phone → Mum: ${code({ nonce: req.nonce.slice(0, 8) + '…', answer, sig: env.sig.slice(0, 14) + '…' })}`);
    const sent = sendTo('mum', { kind: 'reply', env }, 'Reply');
    el.className = `card ${answer === 'yes' ? 'done-green' : 'done-red'}`;
    $('.acts', el)?.remove();
    $('.countdown', el)?.remove();
    $('.d', el).innerHTML = !sent ? '⚠️ Couldn\'t reach Mum\'s phone. Call her directly.'
      : answer === 'yes'
        ? '✓ You confirmed. Mum\'s screen shows green.'
        : `✓ Mum has been warned to hang up.${req.elder ? ' Her guardians were alerted.' : ''}`;
  }
  async fakeBio() {
    const bio = $('.bio', this.el);
    bio.hidden = false;
    bio.classList.remove('ok');
    $('.bio-icon', bio).textContent = '👆';
    $('.bio-msg', bio).textContent = 'Confirm with fingerprint';
    await sleep(650);
    bio.classList.add('ok');
    $('.bio-icon', bio).textContent = '✓';
    $('.bio-msg', bio).textContent = 'Verified';
    await sleep(350);
    bio.hidden = true;
  }

  expirePing(nonce) {
    const el = this.pings.get(nonce);
    if (!el || !$('.acts', el)) return;
    el.classList.add('muted');
    $('.acts', el).remove();
    $('.countdown', el)?.remove();
    $('.d', el).textContent = 'Expired. Mum was told you didn\'t reply.';
  }
  cancelPing(nonce) {
    const el = this.pings.get(nonce);
    if (!el || !$('.acts', el)) return;
    el.classList.add('muted');
    $('.acts', el).remove();
    $('.countdown', el)?.remove();
    $('.d', el).textContent = 'Call ended before you replied.';
  }

  async receiveAlert(env) {
    let p;
    try { p = JSON.parse(env.body); } catch { p = { title: '(unreadable)' }; }
    // 1) signature must come from Mum's device key (pinned when the family paired)
    if (!(await verifyFrom(env, 'mum'))) {
      log('sec', `${this.name}'s phone: ✗ signature check FAILED. Fake alert blocked.`);
      this.add(this.card('rejected', `<div class="app"><span>🛡 Family Verify</span><span>blocked</span></div>
        <div class="t">🛑 Fake alert blocked</div>
        <div class="d">A message saying “${esc(p.title)}” was <b>not signed by Mum's phone</b>. Ignore it and call Mum directly.</div>`));
      this.buzz();
      osNotify('🛑 Fake alert blocked', `“${p.title}” was not signed by Mum's phone.`);
      return;
    }
    // 2) nonce must be fresh (anti-replay), 3) timestamp recent
    if (this.seenNonces.has(p.nonce) || now() - p.ts > 120000) {
      log('sec', `${this.name}'s phone: ✗ nonce ${code(p.nonce)} already used. Replayed alert blocked.`);
      this.add(this.card('rejected', `<div class="app"><span>🛡 Family Verify</span><span>blocked</span></div>
        <div class="t">🛑 Replayed alert blocked</div>
        <div class="d">This alert was already delivered once. Someone is re-sending an old message.</div>`));
      this.buzz();
      osNotify('🛑 Replayed alert blocked', 'Someone is re-sending an old alert.');
      return;
    }
    this.seenNonces.add(p.nonce);
    sessionStorage.setItem(NS('seen'), JSON.stringify([...this.seenNonces]));
    log('sec', `${this.name}'s phone: ✓ signed by Mum's device, nonce fresh`);
    this.showAlert(p, env);
  }

  showAlert(p, env) {
    const old = this.alerts.get(p.callId);
    if (p.level === 'log') {
      const line = document.createElement('div');
      line.className = 'logline';
      line.textContent = `📝 ${p.title} · ${hhmmss(p.ts)}`;
      if (old) old.replaceWith(line); else this.add(line);
      this.alerts.set(p.callId, line);
      return;
    }
    const sigBadge = `<div class="sig">✔ Signed by Mum's phone · ${esc(env.sig.slice(0, 8))}… · ${hhmmss(p.ts)}</div>`;
    let el;
    if (p.level === 'info') {
      el = this.card('info', `<div class="app"><span>🛡 Family Verify · Mum</span><span>now</span></div>
        <div class="t">${esc(p.title)}</div><div class="d">${esc(p.body)}</div>${sigBadge}`);
    } else {
      el = this.card('urgent', `<div class="app"><span>🚨 Family Verify · URGENT</span><span>now</span></div>
        <div class="t">${esc(p.title)}</div><div class="d">${esc(p.body)}</div>
        <div class="acts"><button class="b-green" data-a="call">📞 Call Mum now</button><button class="b-red" data-a="warn">⚠️ Tell Mum to hang up</button></div>
        <div class="shared-box"></div>${sigBadge}`);
      $('[data-a=call]', el).addEventListener('click', (e) => {
        e.target.disabled = true; e.target.textContent = '📞 Calling Mum…';
        this.callMum(p.callId);
      });
      $('[data-a=warn]', el).addEventListener('click', (e) => {
        e.target.disabled = true; e.target.textContent = '✓ Sent to Mum';
        this.warnMum(p);
      });
    }
    if (old) old.replaceWith(el); else this.add(el);
    this.alerts.set(p.callId, el);
    this.refreshShared(p.callId);
    this.buzz(p.level === 'urgent');
    osNotify(p.title, p.body, { urgent: p.level === 'urgent' });
  }

  callMum(callId) {
    log('push', `${this.name} → Mum: incoming voice call`);
    sendTo('mum', { kind: 'guardian-call', callId }, 'Call');
    this.shareWithFamily(callId, `${PEOPLE[this.id].emoji} ${this.name} is calling Mum now`);
  }
  async warnMum(p) {
    const text = p.about === this.id ? 'Hang up. This isn\'t me.' : 'This is a scam. Hang up now.';
    const env = await sign({ type: 'guardian-warning', from: this.id, callId: p.callId, text, ts: now(), nonce: randHex(12) });
    log('push', `${this.name} → Mum's screen: ${code({ text, sig: env.sig.slice(0, 14) + '…' })}`);
    sendTo('mum', { kind: 'guardian-warn', env }, 'Warning');
    this.shareWithFamily(p.callId, `${PEOPLE[this.id].emoji} ${this.name} told Mum to hang up`);
  }
  // shared visibility between guardians ("Daughter is calling Mum now")
  shareWithFamily(callId, text) {
    for (const g of IDS) if (g !== this.id && isOnline(g)) net.raw({ t: 'msg', to: g, body: { kind: 'shared', callId, text } });
  }
  addShared(callId, text) {
    if (!this.shared.has(callId)) this.shared.set(callId, []);
    this.shared.get(callId).push(text);
    this.refreshShared(callId);
  }
  refreshShared(callId) {
    const el = this.alerts.get(callId);
    const box = el && $('.shared-box', el);
    if (!box) return;
    box.innerHTML = (this.shared.get(callId) || []).map((t) => `<div class="shared">${esc(t)}</div>`).join('');
  }

  applyConfig(c) {
    memberCfg = { guardian: !!c.guardian, primary: !!c.primary, elderMode: !!c.elderMode };
    this.renderRole();
  }
  renderRole() {
    $('#member-role').textContent = memberCfg.guardian ? `Guardian${memberCfg.primary ? ' ★ primary' : ''}` : 'Member';
  }
  clear() { this.list.innerHTML = ''; this.pings.clear(); this.alerts.clear(); this.shared.clear(); }
}
let phone = null;

// member: scan own face and send embeddings to Mum (signed)
async function shareMyFace() {
  const msg = (t) => { $('#share-msg').textContent = t; };
  if (!rt.modelsReady) return msg('Face models are still loading…');
  if (rt.enrolling) return;
  closeDrawer();
  const got = await captureSamples(`${nm(ROLE)}`, 3, 'user', msg);
  openDrawer();
  if (!got.length) return msg('⚠️ No face seen. Check lighting and try again.');
  const env = await sign({ type: 'face-enrol', from: ROLE, descriptors: got.map(roundDesc), ts: now() });
  log('face', `${nm(ROLE)}'s phone made ${got.length} embedding(s) like ${code(embPreview(got[0]))}. Photo discarded. Sending the numbers to Mum, signed.`);
  const sent = sendTo('mum', { kind: 'face', env }, 'Face embeddings');
  msg(sent ? `✓ Sent ${got.length} sample(s) to Mum's phone.` : '⚠️ Mum\'s phone is not connected. Try again when it is online.');
}

// ================================================================ UI wiring
function tickClocks() {
  const d = new Date();
  const t = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const date = d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' });
  $$('.clock, .clock-big').forEach((el) => { el.textContent = t; });
  $$('.home-date').forEach((el) => { el.textContent = date; });
}

function syncSettingsUI() {
  if (ROLE !== 'mum') return;
  for (const id of IDS) $(`#g-${id}`).checked = !!store.guardians[id];
  $('#g-primary').value = store.primary || '';
  $('#elder-consent').checked = store.elderConsent;
  if (!store.elderConsent) store.elderMode = false;
  $('#elder-toggle').checked = store.elderMode;
  $('#elder-toggle').disabled = !store.elderConsent;
  $('#elder-switch').classList.toggle('disabled', !store.elderConsent);
  $('#secret-q').value = store.secretQuestion;
  $('#timeout').value = store.replyTimeout;
  $('#timeout-val').textContent = store.replyTimeout;
  $('#caller-id-sel').value = store.callerId;
  $('#notify-blur').checked = store.notifyOnBlur;
  $('#match-th').value = store.matchThreshold;
  $('#match-val').textContent = store.matchThreshold.toFixed(2);
  $('#blur-th').value = store.blurThreshold;
  $('#blur-val').textContent = store.blurThreshold;
  $('#show-debug').checked = store.showDebug;
  $('#cam-facing').value = store.camFacing;
  renderEnrol();
  renderMum();
}
function setStore(patch) { Object.assign(store, patch); save(); syncSettingsUI(); sendConfig(); }

const openDrawer = () => { $('#drawer').hidden = false; };
const closeDrawer = () => { $('#drawer').hidden = true; };

function wireCommon() {
  $('#btn-menu').addEventListener('click', openDrawer);
  $('#btn-close-drawer').addEventListener('click', closeDrawer);
  $('#relay-url').value = cfg.relay;
  $('#btn-relay-save').addEventListener('click', () => {
    cfg.relay = $('#relay-url').value.trim() || cfg.relay;
    saveCfg();
    connect();
  });
  $('#btn-change-role').addEventListener('click', () => {
    if (!confirm('Change whose phone this is?')) return;
    delete cfg.role;
    saveCfg();
    location.href = location.pathname;
  });
  $('#btn-forget-pins').addEventListener('click', () => {
    if (!confirm('Forget the paired keys of the other family phones? They will be re-paired the next time they connect.')) return;
    pins = {};
    localStorage.removeItem(NS('pins'));
    Object.keys(keyMismatch).forEach((k) => delete keyMismatch[k]);
    log('sec', 'Forgot paired family keys. Re-pairing with phones that are online.');
    onPeers(net.peers);
  });
  // keep the screen awake during the demo
  const wake = () => navigator.wakeLock?.request('screen').catch(() => {});
  document.addEventListener('click', wake, { once: true });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) wake(); });
}

function wireMum() {
  for (const row of $$('.enrol-row')) {
    const id = row.dataset.id;
    $('input[type=file]', row).addEventListener('change', async (e) => { await enrolFromFiles(id, [...e.target.files]); e.target.value = ''; });
    $('[data-enrol=cam]', row).addEventListener('click', () => enrolFromCamera(id));
    $('[data-enrol=clear]', row).addEventListener('click', () => {
      store.descriptors[id] = []; save(); renderEnrol(); renderMum();
      log('face', `Deleted all of ${nm(id)}'s face embeddings from this device.`);
    });
  }
  for (const id of IDS) {
    $(`#g-${id}`).addEventListener('change', (e) => {
      setStore({ guardians: { ...store.guardians, [id]: e.target.checked } });
      log('info', `Mum ${e.target.checked ? 'added' : 'removed'} ${nm(id)} as a guardian.`);
    });
  }
  $('#g-primary').addEventListener('change', (e) => setStore({ primary: e.target.value }));
  $('#elder-consent').addEventListener('change', (e) => setStore({ elderConsent: e.target.checked, elderMode: e.target.checked && store.elderMode }));
  $('#elder-toggle').addEventListener('change', (e) => {
    setStore({ elderMode: e.target.checked });
    log('info', `Mum's phone switched to <b>${store.elderMode ? 'Elder' : 'Standard'} Mode</b>.`);
  });
  $('#secret-q').addEventListener('input', (e) => { store.secretQuestion = e.target.value; save(); });
  $('#secret-q').addEventListener('change', () => renderMum());
  $('#timeout').addEventListener('input', (e) => setStore({ replyTimeout: +e.target.value }));
  $('#caller-id-sel').addEventListener('change', (e) => setStore({ callerId: e.target.value }));
  $('#notify-blur').addEventListener('change', (e) => setStore({ notifyOnBlur: e.target.checked }));
  $('#match-th').addEventListener('input', (e) => setStore({ matchThreshold: +e.target.value }));
  $('#blur-th').addEventListener('input', (e) => setStore({ blurThreshold: +e.target.value }));
  $('#show-debug').addEventListener('change', (e) => setStore({ showDebug: e.target.checked }));
  $('#cam-facing').addEventListener('change', (e) => setStore({ camFacing: e.target.value }));

  $('#btn-call').addEventListener('click', () => startCall());
  $('#btn-call2').addEventListener('click', () => startCall());
  $('#btn-end').addEventListener('click', () => endCall('Call ended from demo controls'));
  $('#btn-reset').addEventListener('click', resetDemo);
  $('#btn-expire').addEventListener('click', skipToNoReply);
  $$('input[name=force]').forEach((r) => r.addEventListener('change', (e) => {
    rt.force = e.target.value;
    log('info', rt.force === 'auto' ? 'Caller face: using the live camera.' : `Caller face forced to: <b>${rt.force}</b>`);
  }));
  $('#btn-wipe').addEventListener('click', () => {
    if (!confirm('Forget all enrolled faces and settings on this device?')) return;
    localStorage.removeItem(NS('store'));
    store = loadStore();
    syncSettingsUI();
    log('info', 'All faces and settings deleted from this device.');
  });

  $('#phone-mum').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-action]');
    if (!b) return;
    const a = b.dataset.action;
    if (a === 'hangup') endCall('Mum hung up');
    else if (a === 'answer') answerCall();
    else if (a === 'flip') {
      setStore({ camFacing: store.camFacing === 'user' ? 'environment' : 'user' });
      if (call.active && call.source === 'camera') { try { await attachCamera($('#cam')); } catch (err) { log('warn', esc(err.message)); } }
    } else if (a === 'secret-open') { call.secretOpen = true; renderMum(); }
    else if (a === 'secret-ok') secretAnswer(true);
    else if (a === 'secret-fail') secretAnswer(false);
  });

  document.addEventListener('keydown', (e) => {
    if (e.target.matches('input, select, textarea')) return;
    if (e.key === 'c') startCall();
    if (e.key === 'e') endCall('Call ended (keyboard)');
    if (e.key === 'r') resetDemo();
  });
}

function wireMember() {
  $('#btn-share-face').addEventListener('click', shareMyFace);
}

function resetDemo() {
  endCall('Demo reset');
  rt.force = 'auto';
  $('input[name=force][value=auto]').checked = true;
  $('#mum-toast').hidden = true;
  for (const id of IDS) if (isOnline(id)) net.raw({ t: 'msg', to: id, body: { kind: 'demo', action: 'reset' } });
  logSep('Demo reset');
}

// ---------------------------------------------------------------- first launch: role picker
function showSetup() {
  $('#setup').hidden = false;
  $('#setup-relay').value = defaultRelay();
  $$('.role-pick button').forEach((b) => b.addEventListener('click', () => {
    cfg.role = b.dataset.role;
    cfg.relay = $('#setup-relay').value.trim() || defaultRelay();
    localStorage.setItem(CFG_KEY, JSON.stringify(cfg));
    location.reload();
  }));
}

// ---------------------------------------------------------------- boot
(async function boot() {
  if (!ROLE) { showSetup(); return; }
  cfg.relay = (location.port || params.get('relay')) ? defaultRelay() : (cfg.relay || defaultRelay());
  document.body.classList.add(`role-${ROLE}`, ROLE === 'mum' ? 'is-mum' : 'is-member');
  document.title = `Family Verify · ${nm(ROLE)}`;
  $('#btn-menu').hidden = false;
  $('#drawer-title').textContent = `${PEOPLE[ROLE].emoji} ${nm(ROLE)}'s phone · Settings`;
  wireCommon();
  if (ROLE === 'mum') {
    $('#phone-mum').hidden = false;
    wireMum();
    syncSettingsUI();
  } else {
    $('#phone-member').hidden = false;
    $('#member-name').textContent = `${PEOPLE[ROLE].emoji} ${nm(ROLE)}`;
    phone = new MemberPhone(ROLE);
    phone.renderRole();
    wireMember();
  }
  tickClocks();
  setInterval(tickClocks, 10000);
  if (!window.isSecureContext || !crypto.subtle) {
    $('#net-status').textContent = 'Needs a secure context';
    log('warn', 'This page needs a secure context (localhost or the app) for the camera and WebCrypto.');
    return;
  }
  await initKeys();
  setupNative();
  connect();
  if (params.get('models') !== '0') loadModels(); // ?models=0 = skip face models (automated tests)
  if (window.initWhatsAppMode) window.initWhatsAppMode();
})();
