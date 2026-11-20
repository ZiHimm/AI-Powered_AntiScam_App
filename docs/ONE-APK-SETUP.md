# Setting up 3 phones with ONE app (AntiScam Guard)

> **On branch `jason/step2` (everyone protected):** first launch asks for your **name** instead of a role. Use Mum / Son /
> Daughter for the classic demo, or any names. Every phone: add **your own face** (📷, tick "This is me") and it's shared
> with the family automatically. Tick "I'm a senior citizen" on the phone(s) whose guardians should get alerts.

The same APK goes on all three phones; each phone picks its role on first launch.
**Download:** https://github.com/ZiHimm/AI-Powered_AntiScam_App/releases/download/android-latest/AntiScamGuard.apk
(rebuilt automatically after every change; check the date on the Releases page).

## 0. Relay (laptop)
Either Ben's tunnel (`https://<words>.trycloudflare.com/f/<FAMILY-CODE>`, from the team chat) or the laptop on the same
Wi-Fi (`cd mobile && npm run relay` → `<laptop-ip>:8000`). Use the **same address on all three phones**.
Make sure **no other Mum** is connected (close any Family Verify Mum / browser `?role=mum` tab).

## 1. Install on each phone
Open the download link on the phone → install (allow "Install unknown apps"). If an older AntiScam Guard or Family Verify
was paired before, the first time: **⚙ → Forget paired family keys** (otherwise old keys reject the new phone's messages).

## 2. Mum's phone → choose 👵 **Mum**
1. Home card shows what's missing, one tap each: **Turn on protection** (Accessibility → AntiScam Guard → On;
   Android 13+: if greyed out, App info → ⋮ → Allow restricted settings), **Allow warnings**, **Keep protection on** (battery).
2. **⚙ Settings & setup** → section 2: **📷 Take selfies** for `Son`, `Daughter` (names exactly), and Mum herself with
   **This is me**. For the "scammer shows Son's photo" demo, also add Son from that exact photo/video (🖼).
3. Section 4: relay address → **Connect to family relay**. Son and Daughter should turn 🟢.
4. Home card should say **🟢 Protected**.

## 3. Son's and Daughter's phones → choose 👦 **Son** / 👩 **Daughter**
1. Allow notifications. Tap the orange **Keep alerts on** button if it shows (battery saver).
2. **⚙ Settings** → relay address → **Connect**. Top line: **🟢 Connected · Mum's phone is online**.
3. A quiet "Family alerts are on" notification stays while connected: that's what keeps it working in the background.
4. Have a fingerprint or screen lock set up (Yes/No asks for it).

## 4. Dry run
1. Mum: ⚙ Settings & setup → **Test: a call that looks like Son** → Son's phone pops up "Are you calling Mum right now?"
   with **✔ Signed by Mum's phone** → **No** + fingerprint → Mum: ⛔ SCAM; Daughter: 🚨 urgent alert.
2. A real WhatsApp video call to Mum from a **4th phone** (the "scammer") showing Son's face.

## Changing a phone's role
Mum: ⚙ Settings & setup → section 4 → **Change whose phone this is**. Son/Daughter: ⚙ Settings → **Change whose phone this is**.
