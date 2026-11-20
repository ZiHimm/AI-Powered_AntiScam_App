# AntiScam Guard: Android app (proof of concept)

Runs in the background. When a **WhatsApp, Instagram, Messenger or Telegram** video call is on screen, it looks at
the caller's face (on the phone, never uploaded) and compares it with your family's enrolled faces.

| Situation | What the phone shows |
| --- | --- |
| Face looks like a family member | 📞 *"This video call looks like Son. A face can be faked. If they ask for money, call Son back first."* |
| Screen says "Son" but the face doesn't match | 🚨 *"This may NOT be Son. Hang up and call Son back."* (urgent) |
| Face isn't anyone in the family | *"Caller isn't in your family list."* |
| App hides its video from other apps | Status: *"Can't check this caller."* |

**Not built yet (next phase):** asking the real family member on their own phone *"Are you calling Mum?"* and alerting
guardians (Firebase + signed alerts, as in the web demo).

## Install on a phone (no Play Store)

1. Build it (below) or take `app-debug.apk` from a teammate, and copy it to the phone (USB, Google Drive, Telegram…).
2. Open the APK on the phone and allow **Install unknown apps** for the app you opened it from.
3. Open **AntiScam Guard**:
   - Tap **Allow notifications**.
   - Tap **Turn on call watcher**, then **Installed apps / Downloaded apps → AntiScam Guard**, and switch it on.
     If the switch is greyed out (Android 13+ blocks this for sideloaded apps): **Settings → Apps → AntiScam Guard →
     ⋮ (top right) → Allow restricted settings**, then try again.
4. **Add person** for each family member with 2–3 clear photos. Add **yourself** too with *This is me* ticked, so your
   own camera preview during a call is ignored. Use the name exactly as it appears in your contacts (e.g. "Son").
5. Use **Check a photo** to test matching before a real call. Tune **Match strictness** if needed.

Then make a WhatsApp video call from a family member's phone and watch the notifications and the app's Activity log.

**Needs Android 11+** for automatic face checks (Android 8–10 can install it but can't take screenshots).
On Xiaomi/Oppo/Vivo/Huawei phones, also turn off battery optimisation for the app, or the system may stop the service.

## Build

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
| `assets/mobilefacenet.tflite` | Face embedding model (MobileFaceNet, 112×112 → 192-d), from [MCarlomagno/FaceRecognitionAuth](https://github.com/MCarlomagno/FaceRecognitionAuth) |

### Known limits
- Apps that block screen capture (`FLAG_SECURE`) return a black screenshot. The app detects this and says so.
- Google Play restricts Accessibility Services that aren't for disability support. Fine for sideloading and a
  hackathon; a store release would need a policy review or a different capture approach.
- Face embeddings from this app aren't compatible with the web demo's (different models).
