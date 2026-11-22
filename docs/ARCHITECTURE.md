# AntiScam Guard: architecture

This document explains how AntiScam Guard works technically. For a short overview, start with the [README](../README.md).

## 1. Design principle

A deepfake can look exactly like a family member, so the video isn't used as evidence. The face match only decides
**who to ask**. The decision comes from **the real person's phone**, as a signed answer that a scammer can't produce.

## 2. Components

```
 ┌──────────────┐   WebSocket (wss)    ┌───────────────────┐    WebSocket    ┌──────────────┐
 │ Callee phone │◀────────────────────▶│  Relay (Node.js)  │◀───────────────▶│ Family phone │
 │ AntiScam     │   signed JSON msgs   │  rooms /f/<code>  │                 │ AntiScam     │
 │ Guard        │                      │  Docker + tunnel  │                 │ Guard        │
 └──────────────┘                      └───────────────────┘                 └──────────────┘
        ▲ Accessibility screenshots              ▲
        │ of the video call                      └── Family Verify (web/Capacitor client),
                                                     monitor + scammer console (demo tools)
```

| Component | Path | Tech |
| --- | --- | --- |
| AntiScam Guard | `android/` | Kotlin, Android 8+ (automatic checks need Android 11 / API 30) |
| Relay | `mobile/relay/` | Node.js WebSocket server; Docker; Cloudflare Tunnel for internet access |
| Family Verify | `mobile/www/` | Web / Capacitor app, face-api.js; browser test client |
| Monitor + scammer console | `mobile/www/monitor.*` | Live log of all phones, presenter controls, forged/replayed-alert attacks |
| Invite page | `join.html` | Static page (GitHub Pages) that opens the app from an invite link |
| CI | `.github/workflows/android.yml` | Builds the APK and publishes it to GitHub Releases |

## 3. Call detection and face recognition (on the device)

1. **Call detection.** An `AccessibilityService` watches for a video call screen in WhatsApp, Instagram, Messenger or
   Telegram. A foreground service (`CallWatcherService`) keeps watching while the call app is in front.
2. **Frames.** The service takes a few screenshots per second through the Accessibility screenshot API (Android 11+).
   Frames are processed in memory and never saved. A black frame means the app blocks screenshots, which is reported
   to the user instead of misjudged.
3. **Detection.** Google ML Kit finds the faces in the frame.
4. **Embedding.** Each face is cropped to 112×112 and passed through **MobileFaceNet** (TensorFlow Lite), which outputs a
   **192-dimensional embedding**, normalised to length 1.
5. **Matching.** The embedding is compared with every enrolled person's samples by L2 distance (`FaceStore`):
   - a **match** if the distance is ≤ 1.0 (configurable);
   - **confident enough to ask someone** only if it's ≤ 0.92 *and* at least 0.05 closer than the next person;
   - the result must hold in **2 of the last 3 frames**.
6. **Edge cases.** The phone owner's own face (the camera preview) is skipped; a second family member appearing counts
   as joining, not a new caller; a *different* family member's face replacing the first triggers a new check; a contact
   name on screen that doesn't match the face raises a warning.
7. **Enrolment.** Three selfies via CameraX (or a photo). Only embeddings are stored, in private SharedPreferences.

## 4. The verification flow (`FamilyCheck`)

```
face ≈ Son (confident, 2 of 3 frames)
  → signed "ping" to Son's phone: "Are you calling Mum right now?"  (single-use nonce, short time window)
     ├─ Son: YES (signed) → green  "This is really Son"
     ├─ Son: NO  (signed) → red    "SCAM. Hang up."   + urgent signed alert to Mum's guardians
     └─ no reply / offline → orange: ask the family secret question (+ guardian alert)
```

- **The asked person's side** (`MemberHub`): the question appears as a notification and in the app's inbox. Yes/No is
  only sent after **BiometricPrompt** (fingerprint or device PIN) succeeds.
- **Late answers:** a signed "No" that arrives after the window, or shortly after the call ended, still turns into a
  scam warning. A late "Yes" doesn't override the secret question.
- **Warnings** are drawn by an overlay on top of the call, with **HANG UP** (presses the call app's own end-call
  button when it can find it) and **Dismiss**.
- **Guardians:** if the person receiving the call is a senior (Elder Mode), their chosen guardians get signed alerts. They
  can tap **Call Mum now** or send a signed **Tell Mum to hang up**, which appears over the call.

## 5. Security model

| Threat | Mitigation |
| --- | --- |
| Deepfake of a family member | The face match only picks who to ask; the answer from that person's phone decides. |
| Forged answer or alert | Each phone has an **ECDSA P-256 key in the Android Keystore** (never leaves secure hardware; P1363 signatures). Questions, replies, alerts, profiles and shared faces are signed. |
| Someone pretending to be a family phone | **Key pinning:** each member's public key is pinned on first contact. Invite links and QR codes carry the inviter's key fingerprint, so the first pairing is checked. A changed key is flagged. |
| Replayed old alert | Single-use nonces, remembered on the phone; messages older than 2 minutes are rejected. |
| A member speaking for someone else | The signer must be the member the relay says sent the message, and a member can only ask about their own call. |
| Compromised or curious relay | The relay only forwards messages and makes no decisions. It never sees video, audio, photos or location. |
| Leaking family details from an invite link | Details sit in the URL fragment (`#…`), which browsers never send to a server. |

## 6. Relay and protocol

- **Relay:** each family is a room `/f/<code>`. On `main` the members are the classic `mum` / `son` / `daughter` roles.
  The Step 2 relay (branch `jason/step2`) allows any member id matching `/^[a-z][a-z0-9-]{1,23}$/`, up to 8 per family,
  and still supports the classic roles. A `PUBLIC=1` lockdown mode and a monitor token protect the
  demo tools; `/healthz` reports status; `mobile/relay/check.js` runs self-tests.
- **Hosting:** run locally (`npm run relay`) on the same network as the phones, or in Docker behind a Cloudflare Tunnel,
  so phones on any network (Wi-Fi, mobile data, hotspot) can reach it. Venue Wi-Fi often blocks devices from reaching
  each other; the tunnel avoids that.
- **Messages** (JSON over WebSocket):

| Message | Purpose |
| --- | --- |
| `hello` | Join the room: member id, display name, public key |
| `peers` | Who is in the family and online |
| `ping` (signed) | "Are you calling <name> right now?" |
| `reply` (signed) | Yes / No from the asked person |
| `alert` (signed) | Guardian alert (log, heads-up, urgent) |
| `guardian-call`, `guardian-warn` (signed) | Guardian actions shown on the callee's screen |
| `profile` (signed) | Name, senior, guardians, "check my calls", phone number |
| `face` (signed), `face-request` | Share your own face embeddings with the family / ask for a missing one |
| `expire-ping`, `cancel-ping`, `config` | Question housekeeping and settings |

## 7. Family onboarding (Step 2, branch `jason/step2`)

- **Setup wizard:** about you (name, senior, check my calls) → create or join a family → invite → your face (3 selfies)
  → protection permissions (Accessibility, notifications, battery) → done. It resumes where it left off.
- **Create family:** the app generates the room code and shows a QR code (ZXing) plus a share link.
- **Join:** scan the QR code (ML Kit barcode scanning) or open the link. `join.html` opens the app through the
  `antiscam://join` link, or offers the APK download.
- **Face sharing:** each member enrols their own face on their own phone. It's sent signed (`model: "mobilefacenet-192"`)
  to the family, re-sent when a phone comes back online, re-requested if missing, and deleted everywhere when the owner
  removes it.
- A foreground service (`FamilyService`) keeps the family connection alive in the background.

## 8. Build, test and release

- **Build:** Gradle (`android/`); unit tests with `./gradlew testDebugUnitTest`.
- **CI:** GitHub Actions builds every push. `main` publishes the `android-latest` release, and `jason/step2` publishes
  `android-step2-preview`.
- **Testing:**
  - two Android emulators with a local relay, for end-to-end flows;
  - the relay's self-checks;
  - structured real-phone test rounds (join and face sharing, then real WhatsApp/Messenger calls) with logcat and relay
    traces, in [`docs/test-reports/`](test-reports/).
- **What real phones taught us:** face-ownership and sync bugs that emulators never showed, Xiaomi battery management
  killing the background connection, notifications silently blocked, late replies after hang-up, and venue Wi-Fi
  isolation.

## 9. Limitations and next steps

- Android only: iOS doesn't let apps see other apps' screens.
- Apps that block screenshots can't be checked (the app reports it).
- The demo relay uses a temporary tunnel address; a named tunnel or a cloud host would give a stable address.
- Planned: "check my calls" on by default for everyone, guardian alerts for members who aren't seniors, and Family
  Verify updated to the new face model.
