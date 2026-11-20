# Family Verify: 3 real Android phones + laptop relay

One app, installed on three phones. On first launch each phone picks **Mum**, **Son** or **Daughter**.
The phones talk through a WebSocket relay on the laptop (stand-in for FCM push). The original one-page web
demo at the repo root is untouched and still works as a backup.

- **Real between phones:** "Are you calling Mum?" arrives on Son's actual phone, his answer goes back signed,
  guardians get signed alerts. Each phone has its own ECDSA P-256 key, kept on the phone; the others' keys are
  pinned when they first connect, so a device with a different key is not trusted.
- **Phone feel:** vibration, Android notifications, real fingerprint prompt (BiometricPrompt) before Yes/No.
- **Works in the background:** in the app, the relay connection lives in a native foreground service
  (`RelayKeepAliveService`, quiet "Family Verify is connected" notification), not in the WebView. While another app
  is in front (e.g. WhatsApp), Android would otherwise freeze the app and the WebView puts hidden pages to sleep, so
  requests were never seen. The service stays connected and pops up "Are you calling Mum right now?" and family
  alerts; tapping opens the request in the app. Alert pop-ups are generic on purpose: the signature is checked in the
  app before any alert text is shown. Browsers (tests, monitor) still use a plain WebSocket.
- **Laptop monitor** (`/monitor.html`): live log from every phone, presenter buttons (start call on Mum,
  reset all phones, skip to "no reply"), and a **scammer console** that injects forged and replayed alerts.
- **Faces:** enrol on Mum's phone (camera/photo), or Son/Daughter tap **Scan my face & send** on their own phone
  (only the signed 128-number embedding is sent).
- **Real WhatsApp calls:** install the native detector from [`android/`](../android/) on Mum's phone instead of this
  app, and connect it to this relay (section *4 · Family phones*). It joins as **Mum**, so a real WhatsApp video call
  whose face looks like Son sends this same *"Are you calling Mum?"* to Son's phone. Run only one Mum at a time; if Son or
  Daughter paired with this app's Mum before, tap **Forget paired family keys** on their phone once.

## Run it

```bash
cd mobile
npm install
npm run relay          # copies ../vendor into www/, starts relay on port 8000
```

The relay prints the addresses phones can use, e.g. `ws://192.168.137.1:8000` (Windows Mobile Hotspot) or
`ws://192.168.1.104:8000` (laptop Wi-Fi). The laptop and all phones must be on the **same network**; the laptop's
hotspot is the safest choice at a venue. Allow Node.js through Windows Firewall when asked.

- Monitor for the projector: `http://localhost:8000/monitor.html`
- Test without phones: `http://localhost:8000/?role=mum` (and `?role=son`, `?role=daughter` in other tabs)
- Phone plugged in by USB, not on the same Wi-Fi: `adb reverse tcp:8000 tcp:8000`, then use `ws://localhost:8000`

## Build and install the APK

Needs Android Studio (its bundled JDK 21 + Android SDK).

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd mobile
npm run apk            # copy vendor, cap sync, gradlew assembleDebug
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

Xiaomi phones: turn on **Developer options → Install via USB** first.
App ID is `com.familyverify.demo` (same as `Ben-version`, signed with a different key: uninstall one before
installing the other).

## Demo script

1. Mum: ⚙ → enrol Son's face (or Son: ⚙ → Scan my face & send). Backup: ⚙ → Caller's face → **Son**.
2. **📞 Start demo video call** on Mum (or from the monitor). Son's phone buzzes: *"Are you calling Mum right now?"*
3. Son taps **No, not me** + fingerprint → Mum: full red **SCAM**; Daughter: 🚨 signed urgent alert.
   Daughter taps **Tell Mum to hang up** → Mum sees a verified message from Daughter.
4. Reset (monitor). Call again, Son taps **Yes** → Mum green. Call again, ignore Son → amber + secret question.
5. Airplane mode on Son's phone → monitor shows him offline in ~8 s → call → can't reach Son → amber.
6. Monitor → Scammer console → **Forged "Mum" alert** / **Replay captured alert** → phones show 🛑 blocked.

## Files

```
relay/server.js     WebSocket relay + static server + monitor feed
www/index.html      phone UI (role picker, Mum screen, member screen, ⚙ drawer)
www/app.js          roles, network, keys + pinning, face pipeline, call state machine, alerts
www/mobile.css      full-screen phone styles on top of www/styles.css (copy of the root demo's)
www/monitor.*       laptop monitor + scammer console
android/            Capacitor Android project (camera/notification permissions, keep-screen-on,
                    RelayKeepAliveService + NativeRelayPlugin: relay socket and pop-ups in the background)
```
