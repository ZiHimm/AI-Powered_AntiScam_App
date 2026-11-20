# Zero-Trust Video Verification · Hackathon Demo

## 📱 Running on 3 real Android phones

Each phone runs the same app, **Family Verify** (`FamilyVerify.apk`), and you choose on first launch whether it's **Mum**, **Son** or **Daughter**.
The phones talk to each other through a small relay running on a laptop. Everything stays on the local network,
so no internet is needed (a phone hotspot works fine).

```
 Mum's phone ──┐                     ┌── Son's phone
               ├──  laptop relay  ───┤
               │  (node, port 8787)  └── Daughter's phone
```

**1. Start the relay on the laptop**

```bash
node relay/server.js
```

It prints the address the phones should use, e.g. `http://192.168.43.120:8787`. The laptop and all three phones must be on the
**same Wi-Fi or hotspot**. If Windows asks whether to allow Node.js on the network, allow it on **Private** networks.

**2. Install the app on each phone**

Copy `FamilyVerify.apk` to each phone (USB, Google Drive, WhatsApp to yourself…), tap it, and allow
"Install unknown apps" when Android asks. With USB debugging on, you can instead run
`adb install -r FamilyVerify.apk`.

**3. First launch on each phone**

Type the relay address, then tap **Mum**, **Son** or **Daughter**. The top bar turns green (**Connected**) once the relay sees the phone.
Mum's phone asks for camera permission on the first call or enrolment, so tap **Allow**.
To change a phone's role later, tap the **📱 role button** at the top right.

**4. Set up on Mum's phone** (tap **⚙ Controls**)

- **Enrol family faces:** tap **Webcam** next to Son (with Son looking at Mum's phone) or pick a **Photo**.
- **Mum's camera:** use **Back camera** to point Mum's phone at the "caller" (a teammate or a laptop playing a deepfake video).
- **Incoming call** starts the demo. The controls close so the judges see Mum's screen.

The demo script below is the same, except Son and Daughter tap the buttons on **their own phones**.

**Rebuilding the APK** (needs JDK 21 and the Android SDK; see `android/local.properties`):

```bash
npm install
npm run apk        # → android/app/build/outputs/apk/debug/app-debug.apk
```

**Testing without phones:** run the relay and open `http://localhost:8787/?role=mum`, `?role=son` and `?role=daughter` in three browser tabs.
Opening `http://localhost:8787/` and picking **All three phones on this screen** gives the original one-screen demo.

---

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
