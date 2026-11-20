# AI-Powered Anti-Scam · Zero-Trust Video Call Protection

Scammers now use deepfaked faces and voices on **WhatsApp and Instagram video calls** to pose as a family member and
ask for money. This app runs quietly in the background on an elderly person's Android phone. When a video call
starts, it scans the face on screen **on the device** and checks it against the faces of their family. If the caller
looks like a family member, the app doesn't trust the video. It asks **the real family member, on their own phone**,
*"Are you calling Mum right now?"*

- **Yes:** the call is shown as verified.
- **No:** Mum sees a full-screen **SCAM. Hang up.** warning and her guardians are alerted.
- **No reply:** Mum is told to ask a family secret question: *no answer, no money*.

We don't ask *"is this video fake?"* (a race deepfakes keep winning). We ask *"does the real person confirm they're calling?"*

### How it works

| Step | Android mechanism |
| --- | --- |
| Detect a video call in WhatsApp / Instagram | Accessibility Service watches the foreground app and call screen; Android 12+ also reports an active call |
| See the caller's face | Accessibility screenshots (Android 11+), a few frames per second, processed in memory and never saved |
| Who the caller claims to be | Caller name read from the call screen (e.g. "Son") |
| Face match | On-device face detection and recognition (ML Kit / MediaPipe + a FaceNet-style model); only 128-number embeddings are stored |
| Ask the real person / alert guardians | Firebase push, ECDSA P-256 signed alerts with single-use nonces, so forged or replayed alerts are rejected |
| Family setup | Admin (guardian) creates a family and shares an invite link; members join, mark senior citizens and choose guardians |

**Platform support:** Android (8 or newer for the app, 11 or newer for automatic screen scanning). iOS doesn't let
apps see other apps' screens, so iPhone users get guardian alerts and a manual "verify this caller" flow only.

**Status:** the Android app is in development in [`android/`](android/). The web demo below is the interactive concept
demo: it runs the same verification logic in a browser, with three simulated phones.

---

## Web concept demo

Browser demo with three phones on one page: **Mum** (receiver), **Son** (member/guardian) and **Daughter** (guardian).
The face detection and matching are real and run in the browser with face-api.js. The push network between phones is
simulated, but the nonces and ECDSA P-256 signatures are real (WebCrypto), so forged and replayed alerts genuinely fail.

### Live demo

**https://zihimm.github.io/AI-Powered_AntiScam_App/** (open in Chrome or Safari and allow camera access)

#### Install it on your phone (PWA)
- **iPhone (Safari):** open the link, tap **Share** and then **Add to Home Screen**.
- **Android (Chrome):** open the link, tap **⋮** and then **Install app** (or **Add to Home screen**).

On a phone the page shows one screen at a time with tabs: **Mum · Son · Daughter · Controls · Log**.
A red dot on a tab means that phone just received something. Add `#son`, `#daughter` or `#controls` to the URL to open
straight onto that view (e.g. give each judge a different link).

It opens full-screen like a native app. After the first visit the app and face models (~13 MB) are cached,
so it keeps working offline.

### Run it locally

```bash
./start.sh            # serves http://localhost:8000 and opens it
./start.sh 9000       # different port
```

It must be served from `localhost`, not opened as `file://`, because the camera, WebCrypto and model loading all need it.
Use Chrome. Everything (library + model weights, ~13 MB in `vendor/`) is local, so **no internet is needed at the venue**.

### Pitch flow (about 4 minutes)

1. **Problem (30s):** scammers deepfake a family member's face on a WhatsApp video call and ask for money.
2. **Real app (1 min):** a teammate WhatsApp video-calls "Mum's" Android phone running the [Android app](android/).
   The alert pops up. Mirror the phone to the projector with [scrcpy](https://github.com/Genymobile/scrcpy).
3. **This web simulation (1.5 min):** *"When it looks like Son, here's what happens next"*, using the script below.
   The **Behind the scenes** box under Mum's phone shows each step the background app takes.
4. **Roadmap (30s):** Firebase family linking, real push between phones, Play Store review.

Mum's phone shows the call inside a messaging app (pick WhatsApp / Instagram / Messenger / Telegram in panel 3);
everything from **🛡 AntiScam Guard** appears as notifications on top of the call, as in the real app.

### 2-minute demo script

| Step | Do this | What judges see |
| --- | --- | --- |
| Setup | Panel 1: **🎥 Webcam** on the Son row (the presenter's face becomes "Son"). Panel 2: Son + Daughter ticked as guardians. Elder Mode **on**. | "✓ 3 face samples (embeddings only)". The log shows the 128-number embedding; the image is discarded. |
| 1. Call | **📞 Incoming call** (or press `c`). Look at the camera. | Face matches Son → Son's phone: *"Are you calling Mum right now?"* Daughter gets a heads-up. |
| 2. Spoof (State B) | On Son's phone tap **No, not me**. | Fingerprint check → Mum's screen goes full red **SCAM. Hang up.** Daughter's phone flashes an 🚨 urgent alert, signed by Mum's device. |
| 2b. Guardian | On Daughter's phone tap **📞 Call Mum now** / **⚠️ Tell Mum to hang up**. | Mum gets "Daughter is calling you" / a verified hang-up message. |
| 3. No reply (State C) | **Reset** (`r`), call again, ignore Son's phone (or **⏩ Skip to "no reply"**). | 🟠 "Couldn't check with Son. Ask this. No answer, no money." Guardians get an urgent alert. |
| 4. Blur (State E) | Cover the camera or hold your hand in front of it. | 🟠 "Can't see the caller clearly." |
| 5. Attacks | **😈 Forged alert**, then **🔁 Replay old alert**. | Daughter's phone: "🛑 Fake alert blocked" / "🛑 Replayed alert blocked". |
| Standard Mode | Flip the Elder Mode switch off and repeat. | Smaller banners, user decides, and guardians are **not** alerted. |

Other things to show: **"Son" (copied profile / spoofed)** in panel 3 shows that verification ignores the name and number on screen;
untick **Son's phone online** for an offline phone; a teammate's face (or **Stranger**) gives State D, a normal call with no shield.

#### If something goes wrong on stage
- **Webcam or lighting fails:** use the **Caller's face** buttons (Son / Daughter / Stranger / Blurry / Covered).
  They feed the same state machine, and the log marks them `[forced]`.
- **Face doesn't match:** enrol 2–3 more webcam samples under the venue lighting, or raise *Match threshold* under
  *Tuning & debug* (default 0.50; the same person usually scores 0.2–0.4 and different people 0.6+).
- **Blurry warning fires on a sharp face:** lower *Sharpness threshold*. The live `sharp=` value is shown on Mum's screen.
- Hide the side panel with **Hide controls** for a clean three-phone view. Shortcuts: `c` call, `e` end, `r` reset.

### How it maps to the brief

| Brief | Where |
| --- | --- |
| Decision matrix A–E | `handleObs`, `startVerification`, `onReply` in `app.js` |
| Elder escalation table | `onReply`, `alertGuardians`, `renderMum` |
| Signed emergency alerts (key pair, timestamp, single-use nonce) | `sign`/`verify`, `MemberPhone.receiveAlert` |
| Faces stay on device | `addDescriptor` stores only embeddings in `localStorage`; "Forget everything" wipes them |
| Minimal push payload | Every push is printed in the log: nonce + prompt only |
| Biometric lock on Yes/No | Simulated fingerprint step in `MemberPhone.answer` |
| Blur check (Laplacian variance) | `sharpness()` |

Simulated for the demo (show the real versions in the "production architecture" slide): push delivery (FCM),
key exchange during family setup, biometric prompt, and capturing video from third-party call apps.

### Files

```
index.html     layout: setup panel, 3 phones, log
styles.css     phone mock-ups, Standard/Elder styles
app.js         face pipeline, call state machine, signed alerts, phones
start.sh       local server
sw.js          service worker (offline cache, installable PWA)
manifest.webmanifest  PWA name, icons, colours
icons/         app icons
vendor/        face-api.js 1.7.15 (@vladmandic, MIT) + model weights
```
