// Service worker: makes the demo installable and lets it run offline.
// App shell = network-first (so pushes to GitHub show up), models/library = cache-first (big, never change).
// Bump VERSION when vendor/ files change.
const VERSION = 'v1';
const SHELL = `shell-${VERSION}`;
const VENDOR = `vendor-${VERSION}`;

const SHELL_FILES = [
  './', 'index.html', 'styles.css', 'app.js', 'manifest.webmanifest',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png', 'icons/favicon-32.png',
];
const VENDOR_FILES = [
  'vendor/face-api.js',
  ...['tiny_face_detector', 'ssd_mobilenetv1', 'face_landmark_68', 'face_recognition'].flatMap(m => [
    `vendor/models/${m}_model-weights_manifest.json`, `vendor/models/${m}_model.bin`,
  ]),
];

self.addEventListener('install', e => {
  e.waitUntil((async () => {
    await (await caches.open(SHELL)).addAll(SHELL_FILES);
    await (await caches.open(VENDOR)).addAll(VENDOR_FILES);
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', e => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k !== SHELL && k !== VENDOR) await caches.delete(k);
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;

  if (new URL(req.url).pathname.includes('/vendor/')) {
    e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(res => {
      if (res.ok) { const copy = res.clone(); caches.open(VENDOR).then(c => c.put(req, copy)); }
      return res;
    })));
    return;
  }

  e.respondWith(fetch(req).then(res => {
    if (res.ok) { const copy = res.clone(); caches.open(SHELL).then(c => c.put(req, copy)); }
    return res;
  }).catch(() => caches.match(req, { ignoreSearch: true })
    .then(hit => hit || (req.mode === 'navigate' ? caches.match('index.html') : Response.error()))));
});
