# AntiScam Guard

**AntiScam Guard spots deepfake "family" video calls and asks the real person to confirm on their own phone, before anyone sends money.**

Scammers now video-call people on WhatsApp, Instagram and Messenger wearing a deepfaked face of a son, daughter or
parent: *"Mum, I'm in trouble, I need money now."* A familiar face is no longer proof of who is calling.

AntiScam Guard doesn't try to decide whether the video is fake. That's an arms race deepfake tools keep winning.
Instead, when the caller looks like a family member, it asks **that real person, on their own phone**:

> **"Are you calling Mum right now?"**

Their answer is confirmed with a fingerprint and **cryptographically signed** by their phone, so a scammer can't fake it.

| The real person answers | Mum sees, on top of the video call |
| --- | --- |
| **Yes** | Green: *"This is really Son."* |
| **No** | Red: *"SCAM. Hang up."* Her chosen guardians are alerted straight away. |
| No answer, or their phone is offline | Orange: ask the family secret question. *No answer, no money.* |

Faces never leave the phone. Only small signed messages travel between the family's phones.

---

## How it works

```
 WhatsApp / Instagram / Messenger video call
             │
             ▼
 ┌──────────────────────────┐                        ┌──────────────────────────┐
 │ Mum's phone              │   signed WebSocket     │ Son's phone              │
 │ AntiScam Guard           │◀────── messages ──────▶│ AntiScam Guard           │
 │ 1. sees the call         │      via the relay     │ 3. "Are you calling      │
 │ 2. face ≈ Son (on device)│                        │     Mum right now?"      │
 │ 4. shows green / red     │                        │    fingerprint → Yes/No  │
 └──────────────────────────┘                        └──────────────────────────┘
                     ▲                                          │
                     └──────────── signed answer ───────────────┘
                     (and signed urgent alerts to Mum's guardians)
```

1. **Detect the call.** An Android Accessibility service notices a video call in WhatsApp, Instagram, Messenger or
   Telegram and takes a few screenshots of it, processed in memory and never saved.
2. **Recognise the face on the device.** ML Kit finds the faces in each frame and a MobileFaceNet (TensorFlow Lite) model turns
   each one into a 192-number embedding. It's compared with the family's enrolled faces. A clear match must hold in
   2 of 3 frames before anyone is asked.
3. **Ask the real person.** A signed, single-use question goes to the matched person's phone through a small relay
   server. They answer Yes or No behind a fingerprint or PIN prompt.
4. **Trust only a signed answer.** Mum's phone checks the reply against the key it pinned for that person's phone, then
   shows the result as an overlay on top of the call. If the person receiving the call is a senior, their guardians get
   a signed urgent alert. Guardians can tap **Call Mum now** or send a signed **Tell Mum to hang up**.

The full technical design is in **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

## Security and privacy at a glance

- **Faces stay on the phone.** Only embeddings (numbers, not photos) are stored, in the app's private storage.
- **Hardware-backed keys.** Each phone has an ECDSA P-256 key in the Android Keystore. Questions, answers and alerts
  are all signed.
- **No impersonation.** Each family member's key is pinned on first contact. Family invites carry the inviter's key
  fingerprint, so even the first pairing is checked.
- **No replays.** Every message has a single-use nonce and expires quickly.
- **The relay is untrusted.** It only forwards messages. Every decision is made and checked on the phones.

## Tech stack

| Area | Technology |
| --- | --- |
| Android app | Kotlin, Android 8+ (automatic call checks need Android 11+), AccessibilityService, CameraX, BiometricPrompt, foreground services |
| On-device ML | Google ML Kit face detection, MobileFaceNet (TensorFlow Lite, 192-d embeddings), ML Kit barcode scanning |
| Crypto | ECDSA P-256 (Android Keystore / WebCrypto), key pinning, nonces |
| Relay | Node.js WebSocket server, Docker, Cloudflare Tunnel |
| Companion app | Web / Capacitor app with face-api.js (Family Verify) |
| CI/CD | GitHub Actions builds and publishes the APK to GitHub Releases |

## Try it

**New here? Follow [docs/GET-STARTED.md](docs/GET-STARTED.md)**: install the app on your Android phones, start a relay
with one command, set up a family with a QR code, and try the scam scenario, step by step.

| What | How |
| --- | --- |
| **Android app** | Download **[AntiScamGuard.apk](https://github.com/ZiHimm/AI-Powered_AntiScam_App/releases/download/android-latest/AntiScamGuard.apk)** and install it on each phone (no Play Store needed). Setup: [docs/ONE-APK-SETUP.md](docs/ONE-APK-SETUP.md) |
| **Family preview** (everyone protected, QR-code join) | **[Step 2 preview APK](https://github.com/ZiHimm/AI-Powered_AntiScam_App/releases/download/android-step2-preview/AntiScamGuard.apk)**, from branch `jason/step2` |
| **Browser demo** (no phones needed) | **[Live web demo](https://zihimm.github.io/AI-Powered_AntiScam_App/)**: three simulated phones on one page. Details: [docs/WEB-DEMO.md](docs/WEB-DEMO.md) |
| **Relay** (for real phones) | `cd mobile && npm install && npm run relay`, then enter the printed address in the app. See [mobile/README.md](mobile/README.md) |

## Repository map

| Path | What it is |
| --- | --- |
| [`android/`](android/) | **AntiScam Guard**, the native Android app: call detection, face recognition, family checks, warnings over the call |
| [`mobile/`](mobile/) | **Family Verify** companion app, the **relay** server (`mobile/relay/`), and the demo **monitor + scammer console** |
| root (`index.html`, `app.js`, …) | Browser concept demo (installable PWA), hosted on GitHub Pages |
| [`join.html`](join.html) | Landing page for family invite links (family details stay in the `#` fragment, never sent to a server) |
| [`docs/`](docs/) | [Get started](docs/GET-STARTED.md), [architecture](docs/ARCHITECTURE.md), [project status](docs/STATUS.md), setup guides, real-phone test reports, team design notes |
| [`.github/workflows/`](.github/workflows/) | CI that builds the Android APK |

## Project status

Built during a hackathon, November 2026.

- **`main`**: fixed family roles (Mum is protected; Son and Daughter confirm and act as guardians). Tested with real
  WhatsApp and Messenger calls.
- **`jason/step2`** (preview): every family member is protected, families with any names (up to 8 phones), QR-code and
  invite-link join, a setup wizard, and faces shared automatically between family phones. Tested on real phones
  through a cloud relay.
- **Limitations:** Android only (iOS doesn't let apps see other apps' screens); apps that block screenshots can't be
  checked, and the app says so; the demo relay runs behind a temporary tunnel.

## Team

| | Role |
| --- | --- |
| **Jason** ([@ZiHimm](https://github.com/ZiHimm)) | AntiScam Guard Android app, family protocol, web demo, CI |
| **Brian** | Family Verify companion app, relay, monitor, real-phone testing and reports |
| **Ben** | Relay infrastructure: Docker, Cloudflare Tunnel, cloud deployment |
