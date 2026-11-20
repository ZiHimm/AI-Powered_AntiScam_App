# Zero-Trust Video Verification · Hackathon Demo

Browser demo with three phones on one page: **Mum** (receiver), **Son** (member/guardian) and **Daughter** (guardian).
The face detection and matching are real and run in the browser with face-api.js. The push network between phones is
simulated, but the nonces and ECDSA P-256 signatures are real (WebCrypto), so forged and replayed alerts genuinely fail.

## Run it

```bash
./start.sh            # serves http://localhost:8000 and opens it
./start.sh 9000       # different port
```

It must be served from `localhost`, not opened as `file://`, because the camera, WebCrypto and model loading all need it.
Use Chrome. Everything (library + model weights, ~13 MB in `vendor/`) is local, so **no internet is needed at the venue**.

## 2-minute demo script (Section 9 of the brief)

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

Other things to show: **"Son" (spoofed caller ID)** in panel 3 shows that verification ignores the phone number;
untick **Son's phone online** for an offline phone; a teammate's face (or **Stranger**) gives State D, a normal call with no shield.

### If something goes wrong on stage
- **Webcam or lighting fails:** use the **Caller's face** buttons (Son / Daughter / Stranger / Blurry / Covered).
  They feed the same state machine, and the log marks them `[forced]`.
- **Face doesn't match:** enrol 2–3 more webcam samples under the venue lighting, or raise *Match threshold* under
  *Tuning & debug* (default 0.50; the same person usually scores 0.2–0.4 and different people 0.6+).
- **Blurry warning fires on a sharp face:** lower *Sharpness threshold*. The live `sharp=` value is shown on Mum's screen.
- Hide the side panel with **Hide controls** for a clean three-phone view. Shortcuts: `c` call, `e` end, `r` reset.

## How it maps to the brief

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

## Files

```
index.html     layout: setup panel, 3 phones, log
styles.css     phone mock-ups, Standard/Elder styles
app.js         face pipeline, call state machine, signed alerts, phones
start.sh       local server
vendor/        face-api.js 1.7.15 (@vladmandic, MIT) + model weights
```
