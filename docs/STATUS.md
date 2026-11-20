# Project status: AntiScam Guard / Family Verify

**Last updated:** 21 Nov 2026, 05:15 (at commit `869bb88`) · **Written by:** Brian (with Claude)

One place to see what's built, what's tested on real phones, what's open and who owns it. Details are in the linked reports.

## The idea in one paragraph
A deepfake can look exactly like your son on a video call, so we don't try to spot fakes. When a **real WhatsApp/Messenger
video call** reaches Mum and the caller's face looks like a family member, Mum's phone **asks that person on their own
phone**: *"Are you calling Mum right now?"*. Their **signed** Yes/No (behind a fingerprint) decides what Mum sees
**over the call**: 🟢 "This is really Son" or ⛔ "SCAM. Hang up." No reply means the family secret question. Seniors' guardians
get **signed** alerts; forged and replayed alerts are rejected. Faces never leave the phone, and only small signed messages travel.

## What exists and who owns it

| Part | Folder | Owner | Runs on | Status |
| --- | --- | --- | --- | --- |
| **AntiScam Guard** (detector) | `android/` | Jason | **Mum's** phone (Android 11+) | ✅ real WhatsApp + Messenger calls detected, face match, family check, warning over the call, HANG UP. New: role picker + native Son/Daughter "family mode" (Step 1, `edd12e3`) |
| **Family Verify** | `mobile/` | Brian | **Son's / Daughter's** phones (Capacitor), browsers for testing | ✅ "Are you calling Mum?" + fingerprint + signed reply, guardian alerts, works in the background (native relay service + pop-ups) |
| **Relay** | `mobile/relay/` | Brian → Ben | laptop (Wi-Fi/hotspot), or laptop + Docker + Cloudflare tunnel | ✅ family rooms, locked scammer console, limits, `PUBLIC=1` lockdown, `/healthz` |
| **Monitor + scammer console** | `mobile/www/monitor.*` | Brian / Ben | projector laptop | ✅ live log of all phones, presenter buttons, forged/replayed alert attacks, phone log shown as safe text |
| **Web simulation** | repo root | Jason | GitHub Pages | ✅ explainer and backup |

## How the demo runs today
```
WhatsApp video call ──► Mum's phone (AntiScam Guard)
                           │ face ≈ Son → "Are you calling Mum?" (relay)
                           ▼
Laptop relay ◄──────► Son's phone (Family Verify, or AntiScam Guard family mode) → fingerprint → signed No
   │  (monitor)        Daughter's phone → signed urgent alert, "Tell Mum to hang up"
   ▼
Mum: ⛔ SCAM over the WhatsApp call → HANG UP
```
- Phones tested: Mum = **Samsung Galaxy A55** (Android 14), Son = **Xiaomi** (Android 14), Daughter = **Nothing** (Android 16).
- Relay: laptop on the same Wi-Fi (`ws://<laptop>:8000`), or Ben's free **Cloudflare tunnel** (`https://…trycloudflare.com/f/<code>`,
  address shared in team chat only, never in the repo).
- Make the WhatsApp call from a **4th phone** (the "scammer"), not from a family member's phone.

## Test results (all on real phones unless noted)

| When | What | Result | Report |
| --- | --- | --- | --- |
| 01:00 | Family Verify in 3 browser tabs + relay | ✅ 22 → **33 checks** pass (states A–E, late replies, Standard Mode, forged/replayed alerts) | (script on Brian's laptop) |
| 02:24 | **First real WhatsApp call** → detector → Son's phone | ✅ detected, WhatsApp doesn't block capture, full SCAM flow · ❌ wrong person once, HANG UP 3 taps, 15 s too short | [real-phone-test](test-reports/2026-11-21-real-phone-test.md) |
| 02:43–04:00 | Retest of `f2dc0f7` + `c158247` (R1–R7) | ✅ no wrong pings, 30 s window, Daughter case, stranger ignored, **HANG UP fixed** · ❌ frame error at screen edge, photo of Son weak, one decision per call | [retest](test-reports/2026-11-21-retest.md) · [Jason's response](test-reports/2026-11-21-retest-response.md) |
| 03:45 | Ben's cloud relay (laptop copy) | ✅ 33/33 in laptop mode and in a family room with a token, ✅ **14 security checks** (room isolation, read-only monitor, hostile log HTML) | (scripts on Brian's laptop) |
| 04:00–04:56 | Ben: Docker + Fly.io + Cloudflare tunnel | ✅ 20/20 relay checks through the tunnel, `PUBLIC=1` lockdown · ⏳ phones over the internet not tested yet | [cloud-relay](test-reports/2026-11-21-cloud-relay.md) |
| 04:10–04:46 | Family Verify in the background (Daughter places the WhatsApp call) | ✅ stays online 6+ min with WhatsApp in front, pop-up "Are you calling Mum?", answer from pop-up → SCAM, signed alerts verified | commits `8cf3c06`, `4c44f74` |
| 04:49–05:03 | Retest 2 of `2d2e1ee` | ✅ **face swap re-check** (Daughter → Son → SCAM), 0 frame errors, Messenger detected, one-tap HANG UP · ⏳ join, edge start, Son's photo | [retest-2](test-reports/2026-11-21-retest-2.md) |

## Fixed along the way
| Problem | Fix |
| --- | --- |
| Relay handed out a stale key for an offline phone → pinned by mistake | Relay only advertises keys of connected phones |
| Two phones with the same role kicked each other off forever | Close code 4000 → the replaced phone stops and says why |
| Late answers (fingerprint takes time): wrong "green" on Son's phone, logged as attack on Mum | Late flagged; a late signed **No** still gives SCAM, a late Yes never goes green |
| Detector asked the wrong person (close-up face, 0.97 match) | Stricter match (≤ 0.92 **and** 0.05 gap, 2 of 3 frames), top-two logging |
| HANG UP couldn't find WhatsApp's hidden end-call button | Tap to reveal controls, search all windows, retry (works on "try 2") |
| 15 s reply window too short | 30 s default, setting 10–60 s |
| Frame error when a face crossed the screenshot edge (+ one 13 s stall) | Crop clamped, unit-tested with 200,000 random faces |
| Call locked on the first face (scammer could switch to a deepfake) | Re-check on a **swap**, not on someone **joining** |
| `https://` relay address became `ws://` in the detector | `https://` → `wss://` |
| Scammer console open to anyone on the internet; phone log HTML ran on the monitor | Token-locked inject; log shown as safe text |
| Son/Daughter missed requests while WhatsApp was in front (app frozen, WebView asleep) | Relay socket moved to a native foreground service, pop-ups in the background |
| Son/Daughter rejected Mum's real alerts | Old Mum key pinned from an earlier test → "Forget paired family keys" (setup step, see below) |

## Open items

| # | Item | Owner | Notes |
| --- | --- | --- | --- |
| 1 | **Photo/video of Son doesn't match reliably** (0.98–0.99) | team | Enrol Son from the exact photo/video used on stage, check with "Check a photo", note distances |
| 2 | Test **join** (Daughter steps in next to Son → no new ping) | Brian (test) | |
| 3 | Test **edge start** (face half off-screen from the first frame) | Brian (test) | expect 0 `Frame error` |
| 4 | **Phones over the internet** via Ben's tunnel (Son on mobile data) | Ben + Brian | relay ready, phones not tested |
| 5 | **Which app runs on Son/Daughter on stage**: Family Verify or AntiScam Guard family mode (Step 1)? | Jason + Brian | both work in the background now; pick one and test it with a real WhatsApp call |
| 6 | **Step 2 "everyone protected"** (members instead of fixed roles, signed ping, QR join) | Jason (design), Ben (relay), Brian | needs agreement: [one-app-design](team/2026-11-21-one-app-design.md) |
| 7 | Daughter's phone dropped 04:54–04:59 after a Messenger call | Brian | probably the app was closed; to confirm |
| 8 | "Call Mum now" during a **real** call | Brian (test) | only shown while a call is active |
| 9 | Ping (`"Are you calling Mum?"`) isn't signed | Jason | part of Step 2 |

## Setup checklist before going on stage
1. Relay running (laptop, or tunnel with `PUBLIC=1` + token) → `GET /healthz` OK, monitor shows all three phones 🟢.
2. **Exactly one Mum online** (no browser Mum tab open anywhere).
3. On Son's and Daughter's phones: if they were ever paired with a different Mum, **⚙ → Forget paired family keys** once,
   then check the monitor/log shows "Paired with Mum's device" with Mum's current fingerprint.
4. Mum's phone: call watcher ON (toggle off/on after every install), notifications allowed, battery "always run in background".
5. Faces enrolled on Mum's phone: Son, Daughter, Mum ("This is me"), plus **Son from the stage photo/video**.
6. Son/Daughter: Family Verify open once after install (starts the background service; the "Family Verify is connected"
   notification should be visible).
7. Dry run: "Test: a call that looks like Son" → Son answers from the pop-up → SCAM; then one real WhatsApp call.

## Documents
- **Test reports:** [first real test](test-reports/2026-11-21-real-phone-test.md) · [Jason's response](test-reports/2026-11-21-response.md) ·
  [retest](test-reports/2026-11-21-retest.md) · [retest response](test-reports/2026-11-21-retest-response.md) ·
  [retest 2](test-reports/2026-11-21-retest-2.md) · [cloud relay](test-reports/2026-11-21-cloud-relay.md)
- **Logs:** [first test](test-reports/2026-11-21-detector-log.txt) · [retest](test-reports/2026-11-21-retest-logcat.txt) · [retest 2](test-reports/2026-11-21-retest-2-logcat.txt)
- **Team:** [brief for Ben](team/2026-11-21-brief-for-ben.md) · [update for Jason](team/2026-11-21-update-for-jason.md) ·
  [reply from Jason](team/2026-11-21-reply-from-jason.md) · [Ben's cloud relay summary](team/2026-11-21-ben-cloud-relay-summary.md) ·
  [one-app design](team/2026-11-21-one-app-design.md)
- **How to run:** [android/README](../android/README.md) · [mobile/README](../mobile/README.md) · [mobile/relay/README](../mobile/relay/README.md)
