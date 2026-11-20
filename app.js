'use strict';
/*
 * Zero-Trust Video Verification: hackathon demo
 * ---------------------------------------------
 * All three phones live on this one page. Face detection and recognition are real
 * (face-api.js, running in the browser). The "push" network between phones is simulated
 * with timeouts. The nonces and ECDSA P-256 signatures are real WebCrypto, so the
 * forged/replayed alert attacks really fail verification.
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

const MEMBERS = {
  son: { name: 'Son', emoji: '👦' },
  daughter: { name: 'Daughter', emoji: '👩' },
};
const IDS = Object.keys(MEMBERS);
const nm = (id) => MEMBERS[id].name;
const PUSH_LATENCY = 450; // ms, simulated network delay
const MODEL_URL = 'vendor/models';

// ---------------------------------------------------------------- which phone is this?
// ROLE 'all' = the original one-screen demo (network simulated with timeouts).
// ROLE 'mum' | 'son' | 'daughter' = one real phone; messages travel through the relay (relay/server.js).
const ROLE_KEY = 'ztvv-role-v1';
const RELAY_KEY = 'ztvv-relay-v1';
const qs = new URLSearchParams(location.search);
const ROLE = qs.get('role') || localStorage.getItem(ROLE_KEY) || '';
const RELAY = (qs.get('relay') || localStorage.getItem(RELAY_KEY) || (location.port === '8787' ? location.origin : '')).replace(/\/+$/, '');
const NET = ROLE === 'mum' || ROLE === 'son' || ROLE === 'daughter';
const IS_MUM = ROLE === 'mum' || ROLE === 'all';
const who = (id) => (id === 'mum' ? 'Mum' : nm(id));

// ---------------------------------------------------------------- persistent settings (this device only)
const STORE_KEY = ROLE === 'son' || ROLE === 'daughter' ? `ztvv-demo-v1-${ROLE}` : 'ztvv-demo-v1';
const defaults = () => ({
  descriptors: { son: [], daughter: [] }, // 128-d face embeddings only, never images
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
  facing: 'user', // Mum's camera: 'user' (front) or 'environment' (back)
});
let store = loadStore();
function loadStore() {
  let s;
  try { s = { ...defaults(), ...JSON.parse(localStorage.getItem(STORE_KEY) || '{}') }; } catch { s = defaults(); }
  for (const id of IDS) s.descriptors[id] ||= [];
  return s;
}
const save = () => localStorage.setItem(STORE_KEY, JSON.stringify(store));

// runtime-only state
const rt = { epoch: 0, modelsReady: false, stream: null, enrolling: false, force: 'auto', online: { son: true, daughter: true } };

// ---------------------------------------------------------------- log
function log(tag, html) {
  const li = document.createElement('li');
  li.innerHTML = `<time>${hhmmss()}</time><span class="tag tag-${tag}">${tag}</span><span class="msg">${html}</span>`;
  const ol = $('#log');
  ol.prepend(li);
  while (ol.children.length > 250) ol.lastChild.remove();
}
function logSep(text) {
  const li = document.createElement('li');
  li.className = 'sep';
  li.textContent = `── ${text} ──`;
  $('#log').prepend(li);
}

// ---------------------------------------------------------------- crypto (real WebCrypto ECDSA P-256)
// Every device gets a key pair at setup. Public keys are exchanged when the family circle is formed.
const keys = {};
const enc = new TextEncoder();
const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const ECDSA = { name: 'ECDSA', hash: 'SHA-256' };

async function fingerprint(publicKey) {
  const raw = await crypto.subtle.exportKey('raw', publicKey);
  const h = await crypto.subtle.digest('SHA-256', raw);
  return [...new Uint8Array(h)].slice(0, 6).map((b) => b.toString(16).padStart(2, '0')).join(':');
}
async function initKeys() {
  const gen = () => crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  if (NET) {
    // this phone only makes its own key pair; the others arrive in 'hello' messages
    keys[ROLE] = await gen();
    if (ROLE === 'mum') keys.attacker = await gen();
    myJwk = await crypto.subtle.exportKey('jwk', keys[ROLE].publicKey);
  } else {
    [keys.mum, keys.son, keys.daughter, keys.attacker] = await Promise.all([gen(), gen(), gen(), gen()]);
  }
  await renderKeyInfo();
}
async function renderKeyInfo() {
  const rows = await Promise.all(['mum', ...IDS].filter((id) => keys[id]).map(async (id) => `${who(id)} ${code(await fingerprint(keys[id].publicKey))}`));
  $('#key-info').innerHTML = `Device keys (ECDSA P-256, made at setup):<br>${rows.join('<br>')}`;
}
async function sign(payload, keyPair) {
  const body = JSON.stringify(payload);
  const sig = await crypto.subtle.sign(ECDSA, keyPair.privateKey, enc.encode(body));
  return { body, sig: b64(sig) };
}
async function verify(env, publicKey) {
  try { return await crypto.subtle.verify(ECDSA, publicKey, unb64(env.sig), enc.encode(env.body)); } catch { return false; }
}

// ---------------------------------------------------------------- push network
function send(to, msg, label = 'message') {
  if (IDS.includes(to) && !rt.online[to]) {
    log('warn', `✗ ${nm(to)}'s phone is offline. ${label} not delivered`);
    return false;
  }
  const m = { from: ROLE, ...msg, to };
  if (!NET) {
    const epoch = rt.epoch; // messages in flight are dropped if the demo is reset
    setTimeout(() => { if (epoch === rt.epoch) onMessage(m); }, PUSH_LATENCY);
    return true;
  }
  fetch(`${RELAY}/send`, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify(m) })
    .then((r) => r.json())
    .then((r) => { if (!r.delivered && to !== 'all') log('warn', `✗ ${label}: ${who(to)}'s phone isn't connected right now`); })
    .catch((e) => log('warn', `✗ Can't reach the relay (${esc(e.message)})`));
  return true;
}

// public keys of the other phones, exchanged when each phone joins (relay replays them to late joiners)
let myJwk = null;
const sendHello = () => send('all', { t: 'hello', role: ROLE, jwk: myJwk });
async function onHello(m) {
  if (!m.jwk || m.role === ROLE) return;
  const publicKey = await crypto.subtle.importKey('jwk', m.jwk, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['verify']);
  keys[m.role] = { publicKey };
  log('sec', `Got ${who(m.role)}'s public key ${code(await fingerprint(publicKey))}`);
  renderKeyInfo();
  if (ROLE === 'mum' && m.role !== 'mum') pushSettings(m.role);
}

function connectRelay() {
  const pill = $('#net-status');
  const es = new EventSource(`${RELAY}/events?role=${ROLE}`);
  es.onopen = () => { pill.className = 'pill pill-ok'; pill.textContent = 'Connected'; sendHello(); };
  es.onerror = () => { pill.className = 'pill pill-err'; pill.textContent = 'Reconnecting…'; };
  es.onmessage = (e) => { let m; try { m = JSON.parse(e.data); } catch { return; } onMessage(m); };
}

// Mum tells the family phones their role (Guardian / Member) and mode
function pushSettings(to) {
  if (ROLE !== 'mum') return;
  const s = { guardians: store.guardians, primary: store.primary, elderMode: store.elderMode };
  (to ? [to] : IDS).forEach((id) => send(id, { t: 'settings', s }, 'Settings'));
}

// every message between phones lands here
async function onMessage(m) {
  const p = phones[m.to];
  switch (m.t) {
    case 'hello': return onHello(m);
    // Mum → family phone
    case 'ping': return p?.showPing(m.req);
    case 'expire': return p?.expirePing(m.nonce);
    case 'cancel': return p?.cancelPing(m.nonce);
    case 'alert': return p?.receiveAlert(m.env);
    case 'reset': return p?.clear();
    case 'settings': Object.assign(store, m.s); save(); return IDS.forEach((id) => phones[id].renderRole());
    // family phone → family phone
    case 'shared': applyShared(m.callId, m.text); return p?.refreshShared(m.callId);
    // family phone → Mum
    case 'reply': return IS_MUM && receiveReply(m.from, m.env);
    case 'gcall': return IS_MUM && onGuardianCall(m.from);
    case 'gwarn': return IS_MUM && onGuardianWarn(m.from, m.env);
  }
}

// ---------------------------------------------------------------- face models + camera
const tinyOpts = () => new faceapi.TinyFaceDetectorOptions({ inputSize: 320, scoreThreshold: 0.45 });
const ssdOpts = () => new faceapi.SsdMobilenetv1Options({ minConfidence: 0.5 });

async function loadModels() {
  const pill = $('#model-status');
  if (location.protocol === 'file:') {
    pill.className = 'pill pill-err';
    pill.textContent = 'Open via ./start.sh (http://localhost), not file://';
    log('warn', 'Face models can\'t load from file://. Run <b>./start.sh</b> and open http://localhost:8000. Forced-face demo still works.');
    return;
  }
  try {
    try { await faceapi.tf.setBackend('webgl'); } catch { /* falls back automatically */ }
    await faceapi.tf.ready();
    await Promise.all([
      faceapi.nets.tinyFaceDetector.loadFromUri(MODEL_URL),
      faceapi.nets.ssdMobilenetv1.loadFromUri(MODEL_URL),
      faceapi.nets.faceLandmark68Net.loadFromUri(MODEL_URL),
      faceapi.nets.faceRecognitionNet.loadFromUri(MODEL_URL),
    ]);
    // warm-up so the first real detection isn't slow
    const c = Object.assign(document.createElement('canvas'), { width: 160, height: 160 });
    await faceapi.detectSingleFace(c, tinyOpts()).withFaceLandmarks().withFaceDescriptor();
    rt.modelsReady = true;
    pill.className = 'pill pill-ok';
    pill.textContent = NET ? 'Face AI ready' : `Face models ready (${faceapi.tf.getBackend()})`;
    log('face', 'Face detection + recognition models loaded locally. Nothing is sent to a server.');
  } catch (e) {
    console.error(e);
    pill.className = 'pill pill-err';
    pill.textContent = 'Face models failed to load';
    log('warn', `Model load failed: ${esc(e.message)}. Use the forced-face buttons.`);
  }
}

async function ensureCamera() {
  if (rt.stream) return rt.stream;
  rt.stream = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: store.facing }, audio: false });
  return rt.stream;
}
async function attachCamera(videoEl) {
  videoEl.srcObject = await ensureCamera();
  await videoEl.play().catch(() => {});
  if (videoEl.readyState < 2) await new Promise((r) => videoEl.addEventListener('loadeddata', r, { once: true }));
}
function stopCameraIfIdle() {
  if (call.active || rt.enrolling || !rt.stream) return;
  rt.stream.getTracks().forEach((t) => t.stop());
  rt.stream = null;
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
function sharpness(video, box) {
  const W = video.videoWidth, H = video.videoHeight;
  const x = Math.max(0, box.x), y = Math.max(0, box.y);
  const w = Math.min(W - x, box.width), h = Math.min(H - y, box.height);
  if (w < 8 || h < 8) return 0;
  sctx.drawImage(video, x, y, w, h, 0, 0, 128, 128);
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
function addDescriptor(id, desc, source) {
  store.descriptors[id].push(Array.from(desc, (x) => +x.toFixed(5)));
  save();
  renderEnrol();
  const preview = '[' + Array.from(desc).slice(0, 4).map((x) => x.toFixed(3)).join(', ') + ', … 124 more]';
  log('face', `Enrolled <b>${nm(id)}</b> from ${esc(source)}. Stored embedding ${code(preview)}, image discarded.`);
}

function loadImage(file) {
  return new Promise((res, rej) => {
    const img = new Image();
    img.onload = () => res(img);
    img.onerror = () => rej(new Error('Could not read image'));
    img.src = URL.createObjectURL(file);
  });
}

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

async function enrolFromCamera(id) {
  if (!rt.modelsReady) return enrolMsg('Face models are still loading…');
  if (rt.enrolling) return;
  rt.enrolling = true;
  const wrap = $('#enrol-cam'), v = $('#enrol-video'), msg = $('#enrol-cam-msg');
  wrap.hidden = false;
  try {
    await attachCamera(v);
    let got = 0;
    for (let i = 0; i < 15 && got < 3; i++) {
      msg.textContent = `${nm(id)}: look at the camera… (${got}/3)`;
      await sleep(400);
      const det = await faceapi.detectSingleFace(v, ssdOpts()).withFaceLandmarks().withFaceDescriptor();
      if (det) { addDescriptor(id, det.descriptor, `webcam sample ${got + 1}`); got++; }
    }
    enrolMsg(got ? `✓ Captured ${got} sample(s) for ${nm(id)}` : '⚠️ No face seen. Check lighting and try again.');
  } catch (e) {
    enrolMsg(`⚠️ Camera error: ${e.message}`);
  } finally {
    rt.enrolling = false;
    wrap.hidden = true;
    stopCameraIfIdle();
  }
}
const enrolMsg = (t) => { $('#enrol-msg').textContent = t; };

function renderEnrol() {
  for (const row of $$('.enrol-row')) {
    const n = store.descriptors[row.dataset.id].length;
    const el = $('.enrol-count', row);
    el.textContent = n ? `✓ ${n} face sample${n > 1 ? 's' : ''} (embeddings only)` : 'Not enrolled';
    el.classList.toggle('ok', n > 0);
  }
}

// ================================================================ THE CALL (Mum's device)
const call = {
  active: false, id: null, status: 'idle', answered: false, startedAt: 0,
  target: null, req: null, timer: null, cdTimer: null,
  streak: { key: null, since: 0, count: 0 }, lastObs: null,
  secretOpen: false, warn: null, blurNotified: false, notified: [],
};
// Once verification starts, the result can't be changed by what the camera sees next.
const LOCKED = new Set(['verifying', 'A', 'B', 'C', 'secret-ok', 'secret-fail', 'warned']);
let callGen = 0;

const callerIdText = () => (store.callerId === 'spoofed'
  ? '👦 Son'
  : '+60 11-2345 6789');

async function startCall() {
  if (call.active) return;
  if (NET) { $('.layout').classList.add('no-panel'); panelButtonText(); } // show Mum's screen
  $('#mum-ended').hidden = true;
  Object.assign(call, {
    active: true, id: randHex(4), status: 'scanning', answered: false, startedAt: now(),
    target: null, req: null, streak: { key: null, since: 0, count: 0 }, lastObs: null,
    secretOpen: false, warn: null, blurNotified: false, notified: [],
  });
  const gen = ++callGen;
  $('#mum-home').hidden = true;
  $('#mum-call').hidden = false;
  renderMum();
  logSep(`New call ${call.id}`);
  log('info', `📞 Incoming video call to Mum from <b>${esc(callerIdText())}</b>${store.callerId === 'spoofed' ? ' (caller ID is spoofed; we never trust it)' : ' (unknown number)'}. Mode: <b>${store.elderMode ? 'Elder' : 'Standard'}</b>`);
  try {
    await attachCamera($('#cam'));
    $('#no-cam').hidden = true;
  } catch (e) {
    $('#no-cam').hidden = false;
    log('warn', `Camera unavailable (${esc(e.message)}). Use the "Caller's face" buttons.`);
  }
  scanLoop(gen);
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

async function observe() {
  if (rt.force !== 'auto') {
    return rt.force === 'son' || rt.force === 'daughter'
      ? { kind: 'match', id: rt.force, distance: 0.31, forced: true }
      : { kind: rt.force, forced: true };
  }
  const v = $('#cam');
  if (!rt.modelsReady) return { kind: 'noface', why: 'models loading' };
  if (!v.srcObject || v.readyState < 2) return { kind: 'noface', why: 'no video' };
  const det = await faceapi.detectSingleFace(v, tinyOpts()).withFaceLandmarks().withFaceDescriptor();
  if (!det) return { kind: 'noface' };
  const box = det.detection.box;
  const sharp = sharpness(v, box);
  if (sharp < store.blurThreshold) return { kind: 'blurry', box, sharp };
  const m = bestMatch(det.descriptor);
  if (m.id && m.distance < store.matchThreshold) return { kind: 'match', id: m.id, distance: m.distance, box, sharp };
  return { kind: 'unknown', distance: m.distance, box, sharp };
}

function handleObs(obs) {
  call.lastObs = obs;
  renderDebug(obs);
  if (LOCKED.has(call.status)) return;
  const key = obs.kind === 'match' ? `match:${obs.id}` : obs.kind === 'unknown' ? 'unknown' : 'unclear';
  const s = call.streak;
  if (s.key === key) s.count++;
  else Object.assign(s, { key, since: now(), count: 1 });

  if (obs.kind === 'match' && s.count >= 3) return startVerification(obs.id, obs);               // A/B/C
  if (key === 'unclear' && now() - s.since > 2500 && call.status !== 'E') return setStatus('E'); // E
  if (key === 'unknown' && now() - s.since > 1500 && call.status !== 'D') return setStatus('D'); // D
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
  };
  call.status = 'verifying';
  call.target = id;
  call.req = req;
  log('face', `Face matches enrolled <b>${nm(id)}</b>${obs.forced ? ' [forced]' : ` (distance ${obs.distance.toFixed(2)} &lt; ${store.matchThreshold.toFixed(2)})`}. A deepfake would match too, so now we ask the real ${nm(id)}.`);
  log('push', `Mum → ${nm(id)}'s phone: ${code({ nonce: req.nonce, prompt: req.prompt, expires: `${store.replyTimeout}s` })}. No video, audio or location.`);
  send(id, { t: 'ping', req: { ...req, elder: store.elderMode } }, 'Verification request');

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
}

// signed reply arriving from a family phone
async function receiveReply(from, env) {
  if (!keys[from]) { log('warn', `Reply from ${who(from)} ignored: no public key for that phone yet`); return; }
  if (!(await verify(env, keys[from].publicKey))) {
    log('attack', `✗ Mum's phone rejected a reply claiming to be from ${nm(from)}: bad signature`);
    return;
  }
  const p = JSON.parse(env.body);
  log('sec', `Mum's phone: ✓ reply signature from ${nm(from)}'s device verified`);
  onReply(from, p.nonce, p.answer);
}

function onReply(id, nonce, answer) {
  const req = call.req;
  if (!call.active || !req || req.nonce !== nonce || call.status !== 'verifying') {
    if (answer !== 'timeout') log('attack', `✗ Ignored reply from ${nm(id)}: nonce ${code(nonce.slice(0, 8) + '…')} doesn't match an open request (stale or replayed)`);
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
    send(id, { t: 'expire', nonce }, 'Expiry notice');
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
  if (call.req) send(call.target, { t: 'cancel', nonce: call.req.nonce }, 'Cancel notice');
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
}

// ---------------------------------------------------------------- guardian alerts (signed by Mum's device)
let lastAlertEnv = null;
async function alertGuardians({ level, title, body = '', about = null }) {
  if (!store.elderMode) return;
  let to = IDS.filter((g) => store.guardians[g] && g !== about);
  if (!to.length) to = IDS.filter((g) => store.guardians[g]);
  if (!to.length) { log('warn', 'Mum has not chosen any guardians, so no one was alerted.'); return; }
  const payload = { type: 'guardian-alert', level, title, body, about, callId: call.id, from: 'mum-device', ts: now(), nonce: randHex(12) };
  const env = await sign(payload, keys.mum);
  if (level === 'urgent') lastAlertEnv = env;
  call.notified = [...new Set([...call.notified, ...to])];
  const label = { urgent: '🚨 URGENT alert', info: 'Heads-up', log: 'Silent log' }[level];
  log('sec', `${label} signed by Mum's device → ${to.map(nm).join(' + ')} at the same time ${code({ level, nonce: payload.nonce, sig: env.sig.slice(0, 18) + '…' })}`);
  for (const g of to) send(g, { t: 'alert', env }, label);
  if (level === 'urgent') renderMum();
}

async function sendForgedAlert() {
  const payload = {
    type: 'guardian-alert', level: 'urgent', about: null, callId: 'fake',
    title: '🚨 Mum is in hospital!', body: 'Transfer RM 5,000 to account 8812-3345-01 now for her treatment.',
    from: 'mum-device', ts: now(), nonce: randHex(12),
  };
  const env = await sign(payload, keys.attacker); // scammer doesn't have Mum's private key
  log('attack', '😈 Scammer sends a fake "Mum is in trouble" alert to the guardians, signed with their own key.');
  IDS.filter((g) => store.guardians[g]).forEach((g) => send(g, { t: 'alert', env }, 'Forged alert'));
}
function replayAlert() {
  if (!lastAlertEnv) { log('warn', 'No urgent alert has been sent yet. Run the "Son taps No" scenario first.'); return; }
  log('attack', '😈 Scammer re-sends a real alert they captured earlier (replay attack).');
  IDS.filter((g) => store.guardians[g]).forEach((g) => send(g, { t: 'alert', env: lastAlertEnv }, 'Replayed alert'));
}

// guardian actions (run on the guardian's phone)
const callShared = new Map(); // callId -> [text]
function applyShared(callId, text) {
  if (!callShared.has(callId)) callShared.set(callId, []);
  callShared.get(callId).push(text);
}
function addShared(callId, text, except) {
  applyShared(callId, text);
  for (const g of IDS.filter((x) => x !== except)) {
    if (NET) send(g, { t: 'shared', callId, text }, 'Guardian update');
    else phones[g].refreshShared(callId);
  }
}
function guardianCallsMum(gid, callId) {
  log('push', `${nm(gid)} → Mum: incoming voice call`);
  addShared(callId, `${MEMBERS[gid].emoji} ${nm(gid)} is calling Mum now`, gid);
  send('mum', { t: 'gcall', from: gid, callId }, 'Call');
}
async function guardianWarnsMum(gid, callId, about) {
  const text = gid === about ? 'Hang up. This isn\'t me.' : 'This is a scam. Hang up now.';
  const env = await sign({ type: 'guardian-warning', from: gid, callId, text, ts: now(), nonce: randHex(12) }, keys[gid]);
  log('push', `${nm(gid)} → Mum's screen: ${code({ text, sig: env.sig.slice(0, 14) + '…' })}`);
  addShared(callId, `${MEMBERS[gid].emoji} ${nm(gid)} told Mum to hang up`, gid);
  send('mum', { t: 'gwarn', from: gid, env }, 'Warning');
}

// ...and their effect on Mum's phone
function onGuardianCall(gid) {
  showMumToast(
    `📞 ${nm(gid)} is calling you`,
    call.active ? 'Your family is calling. Switch to them?' : '',
    call.active ? [{ label: `Talk to ${nm(gid)}`, cls: 'b-green', act: () => endCall(`Mum hung up and switched to ${nm(gid)}'s call`) }] : [],
  );
}
async function onGuardianWarn(gid, env) {
  if (!keys[gid] || !(await verify(env, keys[gid].publicKey))) {
    log('attack', `✗ Mum's phone rejected a warning claiming to be from ${nm(gid)}: bad signature`);
    return;
  }
  const p = JSON.parse(env.body);
  log('sec', `Mum's phone: ✓ warning signed by ${nm(gid)}'s device`);
  if (!call.active || call.id !== p.callId) return;
  call.status = 'warned';
  call.warn = { from: gid, text: p.text };
  clearTimeout(call.timer);
  clearInterval(call.cdTimer);
  renderMum();
}

// ---------------------------------------------------------------- Mum rendering
const guardiansList = () => {
  const gs = IDS.filter((g) => store.guardians[g]);
  gs.sort((a, b) => (b === store.primary) - (a === store.primary));
  return gs;
};
const notifiedText = () => (call.notified.length ? `${call.notified.map(nm).join(' and ')} ${call.notified.length > 1 ? 'have' : 'has'} been told.` : '');

function renderMumHome() {
  const gs = guardiansList();
  $('#mum-home-info').innerHTML = `
    ${store.elderMode ? '<b>Elder Mode on</b><br>' : 'Standard Mode<br>'}
    Guardians: ${gs.length ? gs.map((g) => `${MEMBERS[g].emoji} ${nm(g)}${g === store.primary ? ' ★' : ''}`).join(', ') : 'none chosen'}<br>
    Faces enrolled: ${IDS.filter((id) => store.descriptors[id].length).map(nm).join(', ') || 'none yet'}`;
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
  const elder = store.elderMode;
  const phone = $('#phone-mum');
  phone.classList.toggle('elder', elder);
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

// ================================================================ FAMILY MEMBER PHONES
class MemberPhone {
  constructor(id) {
    this.id = id;
    this.el = $(`#phone-${id}`);
    this.list = $('.notifs', this.el);
    this.seenNonces = new Set();
    this.closed = new Set();  // request nonces Mum has expired or cancelled
    this.pings = new Map();   // nonce -> card
    this.alerts = new Map();  // callId -> card
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
      <div class="foot">🔒 Fingerprint required · request ${req.nonce.slice(0, 8)}…</div>`);
    this.pings.set(req.nonce, el);
    $$('button', el).forEach((b) => b.addEventListener('click', () => this.answer(req, b.dataset.a, el)));
    this.add(el);
    this.buzz();
    log('push', `${this.name}'s phone received the verification request`);
    if (this.isClosed(req)) this.expirePing(req.nonce); // arrived after timeout/end
  }
  isClosed(req) { return this.closed.has(req.nonce) || now() > req.expires; }

  async answer(req, answer, el) {
    $$('button', el).forEach((b) => { b.disabled = true; });
    // simulated biometric unlock: a stolen phone can't answer
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

    const env = await sign({ type: 'verify-reply', nonce: req.nonce, answer, from: this.id, ts: now() }, keys[this.id]);
    log('push', `${this.name}'s phone → Mum: ${code({ nonce: req.nonce.slice(0, 8) + '…', answer, sig: env.sig.slice(0, 14) + '…' })}`);
    const late = this.isClosed(req);
    el.className = `card ${late ? 'muted' : answer === 'yes' ? 'done-green' : 'done-red'}`;
    $('.acts', el)?.remove();
    $('.countdown', el)?.remove();
    $('.d', el).innerHTML = late
      ? 'Too late: this request had already expired. Mum still sees a warning.'
      : answer === 'yes'
      ? '✓ You confirmed. Mum\'s screen shows green.'
      : `✓ Mum has been warned to hang up.${req.elder ? ' Her guardians were alerted.' : ''}`;
    send('mum', { t: 'reply', from: this.id, env }, 'Reply');
  }

  expirePing(nonce) {
    this.closed.add(nonce);
    const el = this.pings.get(nonce);
    if (!el || !$('.acts', el)) return;
    el.classList.add('muted');
    $('.acts', el).remove();
    $('.countdown', el)?.remove();
    $('.d', el).textContent = 'Expired. Mum was told you didn\'t reply.';
  }
  cancelPing(nonce) {
    this.closed.add(nonce);
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
    // 1) signature must come from Mum's device key (exchanged at setup)
    if (!keys.mum) { log('warn', `${this.name}'s phone: alert ignored, Mum's public key hasn't arrived yet`); return; }
    if (!(await verify(env, keys.mum.publicKey))) {
      log('sec', `${this.name}'s phone: ✗ signature check FAILED. Fake alert blocked.`);
      this.add(this.card('rejected', `<div class="app"><span>🛡 Family Verify</span><span>blocked</span></div>
        <div class="t">🛑 Fake alert blocked</div>
        <div class="d">A message saying “${esc(p.title)}” was <b>not signed by Mum's phone</b>. Ignore it and call Mum directly.</div>`));
      this.buzz();
      return;
    }
    // 2) nonce must be fresh (anti-replay), 3) timestamp recent
    if (this.seenNonces.has(p.nonce) || now() - p.ts > 120000) {
      log('sec', `${this.name}'s phone: ✗ nonce ${code(p.nonce)} already used. Replayed alert blocked.`);
      this.add(this.card('rejected', `<div class="app"><span>🛡 Family Verify</span><span>blocked</span></div>
        <div class="t">🛑 Replayed alert blocked</div>
        <div class="d">This alert was already delivered once. Someone is re-sending an old message.</div>`));
      this.buzz();
      return;
    }
    this.seenNonces.add(p.nonce);
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
    const sigBadge = `<div class="sig">✔ Signed by Mum's phone · ${env.sig.slice(0, 8)}… · ${hhmmss(p.ts)}</div>`;
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
        guardianCallsMum(this.id, p.callId);
      });
      $('[data-a=warn]', el).addEventListener('click', (e) => {
        e.target.disabled = true; e.target.textContent = '✓ Sent to Mum';
        guardianWarnsMum(this.id, p.callId, p.about);
      });
    }
    if (old) old.replaceWith(el); else this.add(el);
    this.alerts.set(p.callId, el);
    this.refreshShared(p.callId);
    this.buzz(p.level === 'urgent');
  }

  // shared visibility between guardians ("Daughter is calling Mum now")
  refreshShared(callId) {
    const el = this.alerts.get(callId);
    const box = el && $('.shared-box', el);
    if (!box) return;
    box.innerHTML = (callShared.get(callId) || []).map((t) => `<div class="shared">${esc(t)}</div>`).join('');
  }

  setOnline(on) { $('.offline-mask', this.el).hidden = on; }
  clear() { this.list.innerHTML = ''; this.pings.clear(); this.alerts.clear(); this.closed.clear(); }
  renderRole() {
    const g = store.guardians[this.id];
    $(`.role[data-role=${this.id}]`).textContent = g ? `Guardian${store.primary === this.id ? ' ★ primary' : ''}` : 'Member';
  }
}
const phones = Object.fromEntries(IDS.map((id) => [id, new MemberPhone(id)]));

// ================================================================ UI wiring
function tickClocks() {
  const d = new Date();
  const t = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const date = d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' });
  $$('.clock, .clock-big').forEach((el) => { el.textContent = t; });
  $$('.home-date').forEach((el) => { el.textContent = date; });
}

function syncSettingsUI() {
  for (const id of IDS) $(`#g-${id}`).checked = !!store.guardians[id];
  $('#g-primary').value = store.primary || '';
  $('#elder-consent').checked = store.elderConsent;
  if (!store.elderConsent) store.elderMode = false;
  $('#elder-toggle').checked = store.elderMode;
  $('#elder-toggle').disabled = !store.elderConsent;
  $('#elder-switch').classList.toggle('disabled', !store.elderConsent);
  $('#elder-switch').title = store.elderConsent ? '' : 'Mum must consent to Elder Mode first (setup step 3)';
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
  $('#facing-sel').value = store.facing;
  $('#phone-mum').classList.toggle('back-cam', store.facing === 'environment');
  IDS.forEach((id) => phones[id].renderRole());
  renderEnrol();
  renderMum();
}
function setStore(patch) { Object.assign(store, patch); save(); syncSettingsUI(); pushSettings(); }

function wire() {
  // enrolment
  for (const row of $$('.enrol-row')) {
    const id = row.dataset.id;
    $('input[type=file]', row).addEventListener('change', async (e) => { await enrolFromFiles(id, [...e.target.files]); e.target.value = ''; });
    $('[data-enrol=cam]', row).addEventListener('click', () => enrolFromCamera(id));
    $('[data-enrol=clear]', row).addEventListener('click', () => {
      store.descriptors[id] = []; save(); renderEnrol(); renderMum();
      log('face', `Deleted all of ${nm(id)}'s face embeddings from this device.`);
    });
  }
  // guardians: Mum's choice only
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
  $('#facing-sel').addEventListener('change', (e) => {
    setStore({ facing: e.target.value });
    if (rt.stream && !call.active && !rt.enrolling) stopCameraIfIdle();
    else if (rt.stream) log('info', 'Camera change applies to the next call.');
  });

  // demo controls
  $('#btn-call').addEventListener('click', startCall);
  $('#btn-end').addEventListener('click', () => endCall('Call ended from demo controls'));
  $('#btn-reset').addEventListener('click', resetDemo);
  $('#btn-expire').addEventListener('click', () => {
    if (call.status === 'verifying' && call.req) onReply(call.target, call.req.nonce, 'timeout');
    else log('warn', 'Nothing is waiting for a reply right now.');
  });
  $('#btn-forge').addEventListener('click', sendForgedAlert);
  $('#btn-replay').addEventListener('click', replayAlert);
  for (const id of IDS) {
    $(`#online-${id}`).addEventListener('change', (e) => {
      rt.online[id] = e.target.checked;
      phones[id].setOnline(e.target.checked);
      log('info', `${nm(id)}'s phone is now ${e.target.checked ? 'online' : 'OFFLINE'}.`);
    });
  }
  $$('input[name=force]').forEach((r) => r.addEventListener('change', (e) => {
    rt.force = e.target.value;
    log('info', rt.force === 'auto' ? 'Caller face: using the live webcam.' : `Caller face forced to: <b>${rt.force}</b>`);
  }));
  $('#btn-clear-log').addEventListener('click', () => { $('#log').innerHTML = ''; });
  $('#btn-wipe').addEventListener('click', () => {
    if (!confirm('Forget all enrolled faces and settings on this device?')) return;
    localStorage.removeItem(STORE_KEY);
    store = loadStore();
    syncSettingsUI();
    log('info', 'All faces and settings deleted from this device.');
  });
  $('#btn-toggle-panel').addEventListener('click', () => {
    $('.layout').classList.toggle('no-panel');
    panelButtonText();
  });
  $('#btn-role').addEventListener('click', () => {
    if (!confirm('Choose a different role for this phone?')) return;
    localStorage.removeItem(ROLE_KEY);
    location.href = location.pathname;
  });

  // Mum's screen buttons
  $('#phone-mum').addEventListener('click', (e) => {
    const b = e.target.closest('[data-action]');
    if (!b) return;
    const a = b.dataset.action;
    if (a === 'hangup') endCall('Mum hung up');
    else if (a === 'answer') answerCall();
    else if (a === 'secret-open') { call.secretOpen = true; renderMum(); }
    else if (a === 'secret-ok') secretAnswer(true);
    else if (a === 'secret-fail') secretAnswer(false);
  });

  // keyboard shortcuts for the presenter
  document.addEventListener('keydown', (e) => {
    if (!IS_MUM || e.target.matches('input, select, textarea')) return;
    if (e.key === 'c') startCall();
    if (e.key === 'e') endCall('Call ended (keyboard)');
    if (e.key === 'r') resetDemo();
  });
}

function resetDemo() {
  endCall('Demo reset');
  rt.epoch++;
  IDS.forEach((id) => {
    phones[id].clear();
    rt.online[id] = true;
    $(`#online-${id}`).checked = true;
    phones[id].setOnline(true);
    if (NET) send(id, { t: 'reset' }, 'Reset');
  });
  rt.force = 'auto';
  $('input[name=force][value=auto]').checked = true;
  $('#mum-toast').hidden = true;
  callShared.clear();
  logSep('Demo reset');
}

// ---------------------------------------------------------------- role picker
function panelButtonText() {
  const hidden = $('.layout').classList.contains('no-panel');
  $('#btn-toggle-panel').textContent = NET ? (hidden ? '⚙ Controls' : '✕ Close') : (hidden ? 'Show controls' : 'Hide controls');
}
function showRolePicker(msg = '') {
  document.body.classList.add('picking');
  $('#role-picker').hidden = false;
  $('#relay-input').value = RELAY;
  $('#picker-msg').textContent = msg;
  $$('#role-picker [data-role]').forEach((b) => b.addEventListener('click', () => {
    const role = b.dataset.role;
    const relay = $('#relay-input').value.trim().replace(/\/+$/, '');
    if (role !== 'all' && !/^https?:\/\/[^/]+$/.test(relay)) {
      $('#picker-msg').textContent = 'Enter the relay address shown on the laptop, e.g. http://192.168.1.20:8787';
      return;
    }
    localStorage.setItem(ROLE_KEY, role);
    if (relay) localStorage.setItem(RELAY_KEY, relay);
    location.href = location.pathname;
  }));
}

// ---------------------------------------------------------------- boot
(async function boot() {
  if (!ROLE) return showRolePicker();
  if (NET && !RELAY) return showRolePicker('Enter the relay address first.');
  document.body.classList.add(`role-${ROLE}`);
  if (NET) {
    document.body.classList.add('solo');
    $(`#phone-${ROLE}`).closest('.phone-col').classList.add('me');
    $('.layout').classList.add('no-panel');
    $('#net-status').hidden = false;
    $('#btn-role').textContent = `📱 ${who(ROLE)}`;
  }
  panelButtonText();
  wire();
  tickClocks();
  setInterval(tickClocks, 10000);
  syncSettingsUI();
  if (!window.isSecureContext || !crypto.subtle) {
    log('warn', 'This page needs a secure context (http://localhost) for the camera and WebCrypto. Run ./start.sh.');
    return;
  }
  await initKeys();
  log('sec', NET
    ? `This phone (${who(ROLE)}) made its own ECDSA P-256 key pair. Only the public key is shared.`
    : 'Each device made its own ECDSA P-256 key pair. Public keys were exchanged when the family circle was set up.');
  if (NET) connectRelay();
  if (IS_MUM) loadModels();
})();
