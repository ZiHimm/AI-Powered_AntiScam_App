# AntiScam Guard: Android app (one app for the whole family)

**One APK for everyone.** On first launch it asks **"Whose phone is this?"**:

| Choice | What the phone does |
| --- | --- |
| 👵 **Mum** | **Protected phone.** Watches WhatsApp / Instagram / Messenger / Telegram video calls, checks the caller's face on the phone, asks the real family member, and shows warnings over the call. Home screen: a big Protected / Partly protected / Not protected card with one-tap fixes. |
| 👦 **Son** / 👩 **Daughter** | **Family mode.** Stays connected in the background (foreground service). "Are you calling Mum right now?" pops up, Yes/No needs the **fingerprint** (or screen lock), and the answer is signed. Guardian alerts are shown only after the signature, nonce and age checks pass, with **Call Mum now** / signed **Tell Mum to hang up**. Forged and replayed alerts are blocked. |

Change it later: Mum: ⚙ Settings → section 4 → *Change whose phone this is*; family mode: ⚙ Settings.
Same protocol as Family Verify (`mobile/`), so the two apps can be mixed on one relay. The design for **everyone
protected** (Step 2) is in [`docs/team/2026-11-21-one-app-design.md`](../docs/team/2026-11-21-one-app-design.md).

## Mum's phone: the call detector

Runs in the background. When a **WhatsApp, Instagram, Messenger or Telegram** video call is on screen, it looks at
the caller's face (on the phone, never uploaded) and compares it with your family's enrolled faces.

Warnings are drawn **on top of the video call** (big text, **HANG UP** / **Dismiss** buttons), plus a notification.
**HANG UP** presses the call app's own end-call button when it can find it.

| Situation | What the phone shows |
| --- | --- |
| Face looks like a family member | 📞 *"This video call looks like Son. A face can be faked. If they ask for money, call Son back first."* |
| Screen says "Son" but the face doesn't match | 🚨 *"This may NOT be Son. Hang up and call Son back."* (urgent) |
| Face isn't anyone in the family | *"Caller isn't in your family list."* |
| App hides its video from other apps | Status: *"Can't check this caller."* |

### Ask the real family member (family relay)

Connected to the laptop relay from [`mobile/`](../mobile/), this app acts as **Mum's phone** in the family system.
Son's and Daughter's phones run the `mobile/` app. When a WhatsApp call's face looks like Son:

1. Son's phone buzzes: *"Are you calling Mum right now?"* (single-use nonce, 15 s)
2. Mum sees **"Checking with Son…"** over the call, with a countdown.
3. Son's signed **Yes** → 🟢 *"This is really Son"*. **No** → 🔴 *"SCAM. Hang up."* No reply / Son offline →
   🟠 the family secret question, with **Answered right / Couldn't answer** buttons.
   A **late signed "No"** (after the 15 s window, same call) still switches Mum to SCAM. It's accepted once; a late
   "Yes" doesn't override the secret question. The monitor's **Skip to "no reply"** button ends the countdown.
4. In Elder Mode, guardians get signed alerts and can reply with **Call Mum now** or a signed **Tell Mum to hang up**,
   which appears over Mum's call.

Messages use the same format and checks as `mobile/www/app.js`: Mum's key is an ECDSA P-256 key in the Android
Keystore. Son's and Daughter's keys are pinned the first time they're seen, and every reply and alert is signature-checked.

**Set up** (Mum's phone, section *4 · Family phones*):
1. Start the relay on the laptop (`cd mobile && npm install && npm run relay`) and type the address it prints
   (e.g. `192.168.1.104:8000`). Turn on **Connect to family relay**.
2. Enrol Son's and Daughter's faces here, named exactly **Son** and **Daughter**.
3. **Test: a call that looks like Son** runs the whole flow without a WhatsApp call.

**Don't run the `mobile/` app as Mum at the same time.** The relay allows one Mum, and this app stops reconnecting if
another phone takes the role. If Son's or Daughter's phone was paired with the `mobile/` Mum app before, tap
**Forget paired family keys** there once, so it pairs with this phone's key instead.

## Install (no Play Store)

Install the same APK on every phone, then pick whose phone it is. **Son / Daughter:** allow notifications, open
⚙ Settings, enter the same relay address as Mum's phone and tap Connect. That's all. The steps below are for **Mum's phone**.

### Mum's phone

1. On the phone, download **[AntiScamGuard.apk](https://github.com/ZiHimm/AI-Powered_AntiScam_App/releases/download/android-latest/AntiScamGuard.apk)**
   (GitHub builds it automatically after every push; see *Releases → Android app (latest build)*).
2. Open the APK on the phone and allow **Install unknown apps** for the app you opened it from.
3. Open **AntiScam Guard**:
   - Tap **Allow notifications**.
   - Tap **Turn on call watcher**, then **Installed apps / Downloaded apps → AntiScam Guard**, and switch it on.
     If the switch is greyed out (Android 13+ blocks this for sideloaded apps): **Settings → Apps → AntiScam Guard →
     ⋮ (top right) → Allow restricted settings**, then try again.
4. **Add person** for each family member, then **📷 Take selfies**: they look at the front camera and 3 face
   samples are saved automatically (or **Choose photo** to use gallery photos). Add **yourself** too with *This is me* ticked, so your
   own camera preview during a call is ignored. Use the name exactly as it appears in your contacts (e.g. "Son").
5. Use **Check a photo** to test matching, and **Preview the warning** to see the on-call warning without a call.
   Tune **Match strictness** if needed.

Then make a WhatsApp video call from a family member's phone and watch the notifications and the app's Activity log.

**Needs Android 11+** for automatic face checks (Android 8–10 can install it but can't take screenshots).
On Xiaomi/Oppo/Vivo/Huawei phones, also turn off battery optimisation for the app, or the system may stop the service.

## Build

Every push to `main` that touches `android/` is built by GitHub Actions (`.github/workflows/android.yml`) and published
as the `android-latest` release. All builds share `app/debug.keystore`, so any build installs over any other.

Open the `android/` folder in Android Studio and press ▶ with a phone connected, or from a terminal:

```bash
cd android
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## How it works

```
CallWatcherService (AccessibilityService)
  every 1 s: is a call app in front AND AudioManager.mode == MODE_IN_COMMUNICATION?
     └─ yes → takeScreenshot()  (Android 11+, in memory only)
              → ML Kit face detection → MobileFaceNet (TFLite) embedding
              → nearest enrolled person (L2 distance ≤ threshold); faces marked "me" are skipped
              → read on-screen text for an enrolled name (the contact name the caller is shown as)
              → 2 of the last 3 frames agree → notification
```

| File | What it does |
| --- | --- |
| `CallWatcherService.kt` | Call detection, screenshots, decision + alerts |
| `FaceEngine.kt` | ML Kit detector + MobileFaceNet embeddings (one worker thread) |
| `FaceStore.kt` | Enrolled people, embeddings and threshold in private app storage |
| `MainActivity.kt` | Setup screen: permissions, enrol faces, test a photo, live log |
| `Notifier.kt` | Alert and status notifications |
| `WarningOverlay.kt` | Big warning drawn over the call (accessibility overlay, no extra permission) |
| `EnrolActivity.kt` | Front-camera enrolment: 3 face samples, no photos saved |
| `FamilyLink.kt` | WebSocket client for the `mobile/` relay as Mum's phone; key pinning; settings |
| `FamilyCheck.kt` | Ask the real family member, handle signed replies and guardian messages, alert guardians |
| `DeviceKey.kt` | Mum's ECDSA P-256 key in the Android Keystore; WebCrypto-compatible signatures (P1363) and JWK |
| `assets/mobilefacenet.tflite` | Face embedding model (MobileFaceNet, 112×112 → 192-d), from [MCarlomagno/FaceRecognitionAuth](https://github.com/MCarlomagno/FaceRecognitionAuth) |

### Known limits
- Apps that block screen capture (`FLAG_SECURE`) return a black screenshot. The app detects this and says so.
- Google Play restricts Accessibility Services that aren't for disability support. Fine for sideloading and a
  hackathon; a store release would need a policy review or a different capture approach.
- Face embeddings from this app aren't compatible with the web demo's (different models).
