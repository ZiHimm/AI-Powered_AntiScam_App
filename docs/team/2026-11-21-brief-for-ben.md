# Brief for Ben: cloud family relay (+ QR pairing if time allows)

## Where the project is now (pull `main`, latest `f2dc0f7` or newer)

Don't build on `Ben-version`. It started from the very first commit, and merging it would overwrite `android/` and the
root web demo. Your late-ping fix idea is already in `main`, in both apps. Start a **new branch from today's `main`**,
e.g. `ben/cloud-relay`.

| Folder | What it is | Owner |
| --- | --- | --- |
| `android/` | **AntiScam Guard**, Jason's native Kotlin app on **Mum's** phone. Watches real WhatsApp/Instagram video calls, matches the caller's face, joins the relay as `mum` (`FamilyLink.kt`, `FamilyCheck.kt`) | Jason. **Don't edit** |
| `mobile/` | **Family Verify** (Capacitor, Brian), on **Son's/Daughter's** phones: "Are you calling Mum?", signed Yes/No with fingerprint, guardian alerts. Plus `mobile/relay/server.js` (the relay) and `mobile/www/monitor.html` (laptop monitor + scammer console) | Brian. Coordinate before editing `www/app.js` |
| root (`index.html`, `app.js`, …) | Web simulation on GitHub Pages | leave as is |
| `docs/test-reports/` | Real-phone test results | |

**Status:** tested on 3 real phones. A real WhatsApp video call to Mum → face matched → Son's phone asked → signed "No" →
SCAM over the call, plus signed alerts to Daughter. See `docs/test-reports/2026-11-21-real-phone-test.md`.

## The problem to solve

Everything runs through `mobile/relay/server.js` **on a laptop**, so all phones must be on the same Wi-Fi/hotspot as that
laptop, and the demo dies if the laptop sleeps or the venue Wi-Fi isolates devices. **Task: host the relay on the internet
over `wss://`, safely**, so the phones work on any network (including mobile data), and keep the laptop relay as a
fallback.

## Protocol (must stay compatible: both apps already speak it)

WebSocket at `<relay address>/ws`. JSON messages:

```
phone → relay   {t:"hello", role:"mum"|"son"|"daughter", pub:<ECDSA P-256 JWK>, name}
relay → all     {t:"peers", peers:{mum:{online,pub,name}, son:{…}, daughter:{…}}}   // pub only for ONLINE phones
phone → relay   {t:"msg", to:<role>, body:{kind:…}}  → relay → target  {t:"msg", from:<role>, body}
relay → sender  {t:"undeliverable", to, kind}                                  // target not connected
phone → relay   {t:"log", tag, html}                                           // forwarded to monitors
monitor → relay {t:"hello", role:"monitor"};  {t:"inject", from, to, body}     // scammer console + presenter
```

Rules already in the relay that must survive: one connection per role (a newer one replaces the old with close code
**4000**; both apps stop reconnecting on 4000); ping every 4 s, so a vanished phone is dropped in about 8 s; only advertise
keys of phones that are online (an old key for an offline phone gets pinned by mistake). The relay never sees faces or
video, and it doesn't vouch for anything: the phones check signatures themselves.

## What to build

1. **Family rooms in the URL path**: `wss://<host>/f/<FAMILY-CODE>/ws`. Each code is its own room (its own
   mum/son/daughter slots, peers and monitors). This needs **no app changes**: both apps just add `/ws` to whatever
   relay address the user types, so the user types `wss://<host>/f/K7Q2-9XPD`. Use long random codes (≥ 8 chars,
   unguessable), because the code is the only thing keeping strangers out of a family. Keep plain `/ws` working
   (the default room) for the laptop setup.
2. **Lock down `inject`.** On the internet, the scammer console is a real attack tool. Only allow `role:"monitor"` +
   `inject` with a secret (env var `MONITOR_TOKEN`, sent in the monitor hello). Without the token, monitors are read-only.
3. **Limits**: max payload (already 2 MB; 64 KB is plenty, except face embeddings, which are ~3 KB), max connections per
   room, and a simple per-IP rate limit.
4. **Monitor page under the room**: `https://<host>/f/<code>/monitor.html`. `mobile/www/monitor.js:32` hard-codes
   `ws://${location.host}/ws`. Change it to `wss:` on https pages and use the room path. That's the only `mobile/www`
   file you need to touch. Tell Brian before you push it.
5. **Deploy** on a host with **WebSockets and no cold start** (e.g. Fly.io or Railway). Free tiers that sleep after
   15 min take ~50 s to wake up, which would ruin the demo. Put the deploy steps in `mobile/relay/README.md`.
6. **Health endpoint** `/healthz`, so we can check it before going on stage.

### Watch out
- `android/.../FamilyLink.kt:94` turns `https://` into `ws://` (not `wss://`). So **on Mum's phone, type the address
  starting with `wss://`**, which is kept as is. Report this to Jason in your PR description rather than editing `android/`.
- The Capacitor app (`mobile/capacitor.config.json`) allows cleartext for the laptop relay; `wss://` works too.
- The laptop relay must keep working unchanged (`cd mobile && npm run relay`): it's the stage fallback.

## How to test
1. Run your relay locally, open `http://localhost:8000/f/TEST1234/monitor.html`, plus `/?role=mum`, `?role=son`,
   `?role=daughter` (set the relay address to `ws://localhost:8000/f/TEST1234` in ⚙ if needed). In Mum's tab:
   ⚙ → Caller's face → **Son** → **Demo call**. Son taps **No**. Expect SCAM on Mum and an urgent alert on Daughter.
2. A second room (`/f/OTHER567`) must **not** see the first room's peers or messages.
3. Monitor without the token: the presenter/scammer buttons must do nothing.
4. Deployed: Son/Daughter phones on **mobile data**, Mum's phone on Wi-Fi, all using the `wss://…/f/<code>` address.
   Repeat test 1, then a real WhatsApp call with the detector (it joins as Mum).
5. Brian has an automated 33-check browser test (3 tabs + monitor). Ask him to run it against your relay before you merge.

## Stretch: QR-code family pairing (only after the relay is solid)
Today phones trust each other's key the first time they see it ("trust on first use"). Better: Mum's phone shows a QR code
with its key fingerprint + family code, and Son/Daughter scan it to pin Mum's key and join the room. Agree on the design
with Brian (Family Verify) **and** Jason (the detector has its own pinning in `FamilyLink.kt`) before starting.

## Hand-off
Open a **pull request** from your branch into `main`, with what you changed, the deployed address, how to deploy, and the
test results. Don't push to `main` directly.
