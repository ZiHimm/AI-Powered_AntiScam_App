# Real-phone test report: WhatsApp detector + family relay

**Date:** 21 Nov 2026, 02:10–02:28 · **Tested by:** Brian (with Claude) · **Detector code:** not changed, as you asked

## Setup

| Role | Device | App |
| --- | --- | --- |
| Mum | Samsung Galaxy A55 (SM-A556E), Android 14 | AntiScam Guard 0.1.0, release APK built from `c4c89da` |
| Son, Daughter | Xiaomi (Android 14) and Nothing (Android 16) | Family Verify (`mobile/`), build from before `838f91f` |
| Laptop | Windows, relay from `mobile/relay` on port 8000 | All four on the same Wi-Fi |

The call watcher was turned on by hand in Accessibility (adb installs don't trigger "restricted settings"). Notifications were
granted and battery optimisation was turned off. Enrolled: son, daughter, mom ("This is me"), 3 front-camera selfies each.
Caller: the person enrolled as Son, calling on a separate WhatsApp number.

## Summary

**The step that couldn't be run on the emulator works on a real phone.** A real WhatsApp video call was detected, the
screenshots contained the caller's video (WhatsApp does **not** block capture), the face was matched, Son's phone was asked,
his signed "No" came back, and Mum got SCAM over the call. Daughter got signed alerts throughout.

Three things need fixing (details and evidence below). In order of how much they'd hurt the demo:
1. **The first call asked the wrong person** (Daughter instead of Son).
2. **HANG UP needed 3 taps**, because WhatsApp hides its end-call button.
3. **15 s to reply is too short** in real use. We missed it once ourselves.

## Results

| Test | Result |
| --- | --- |
| Relay connection, pairing, key pinning (all 3 phones) | ✅ Mum key `e8:5e:a6:3b:a9:0a`, pinned Son `16:14:70:a0:e1:a6`, Daughter `52:5a:ad:f9:11:51` |
| "Test: a call that looks like Son" → Son taps No | ✅ ping → signed reply verified → SCAM → urgent alert to Daughter |
| Real WhatsApp call detected | ✅ every time, about 0–2 s after connecting. `dumpsys audio`: `MODE_IN_COMMUNICATION`; top activity `com.whatsapp/.calling.ui.VoipActivityV2` |
| Screenshots contain the video (no FLAG_SECURE) | ✅ faces found on every frame. Mum's own preview was correctly not matched (`closest mom 1.07`) |
| Call 1 (02:24), caller = Son, face close-up from below | ❌ **matched Daughter**, asked Daughter's phone (details below) |
| Call 2 (02:25), Son, face straight on | ✅ matched Son 0.82 / 0.85 and asked Son. We answered too late, so: no reply → secret question → "Call ended (WhatsApp)" detected ✅ |
| Call 3 (02:27), Son, straight on, **Son taps No** | ✅ full flow (timeline below) |
| HANG UP on the SCAM warning | ⚠️ worked on the **3rd** tap |
| Daughter's phone shows "✔ Signed by Mum's phone" | ⚠️ not confirmed on the phone (Mum's log shows the alerts were sent) |

### Call 3: the full flow on a real call

```
02:27:29.081  📞 WhatsApp call detected. Checking the caller's face…
02:27:29.488  Face not in family (closest mom 1.07)
02:27:30.511  Face ≈ son (distance 0.79)
02:27:31.527  Face ≈ son (distance 0.79)
02:27:31.531  WhatsApp call: face matches Son (seen in 2 of the last 3 frames) … now we ask the real Son.
02:27:31.531  Mum → Son's phone: "Are you calling Mum right now?" nonce 30602264…, 15s.
02:27:31.568  Heads-up signed by Mum's phone → Daughter
02:27:32–37   Face ≈ son 0.74, 0.71, 0.88, 0.94, 0.89, 0.75
02:27:38.279  ✓ Reply signature from Son's phone verified
02:27:38.287  🔴 Son says it is NOT them. Scam call.
02:27:38.323  🚨 Urgent alert signed by Mum's phone → Daughter
02:27:44.787  Couldn't find the end-call button; asked the user to tap it.
02:27:47.624  Couldn't find the end-call button; asked the user to tap it.
02:27:49.567  Hung up the call for the user.
02:27:50.860  📴 Call ended (WhatsApp).
```

2.4 s from the call connecting to the question reaching Son's phone, and 6.7 s from there to SCAM.

## Issue 1: the first call matched the wrong person

The caller was Son, but the camera was very close, angled from below, with the chin cut off (very different from the enrolment
selfies). The first two frames scored just under the match limit for **Daughter**, so 2-of-3 locked onto Daughter.
Son became the clearly better match a few seconds later, after the ping had already gone to Daughter:

```
02:24:01.333  📞 WhatsApp call detected.
02:24:03.223  Face ≈ daughter (distance 0.98)
02:24:03.951  Face ≈ daughter (distance 0.97)
02:24:03.953  WhatsApp call: face matches Daughter (seen in 2 of the last 3 frames) … ask the real Daughter.
02:24:05.016  Face not in family (closest daughter 1.01)
02:24:06.007  Face ≈ daughter (distance 0.96)
02:24:06.992  Face not in family (closest son 1.12)
02:24:08.028  Face ≈ son (distance 0.95)
02:24:09.049  Face ≈ son (distance 0.84)
02:24:10.064  Face ≈ son (distance 0.92)
02:24:11.120  Face not in family (closest son 1.02)
```

The match limit looks like about 1.0. With straight-on video, real Son matches were **0.71–0.94**; the wrong matches were
**0.96–0.98**. So the wrong decision came from matches just inside the limit, made early.

Ideas (your call):
- **Stricter limit** for decisions that trigger a ping (e.g. ≤ 0.90), while keeping the loose one for "looks like" hints.
- **Margin test:** only accept if the best person beats the second-best person by a clear gap. The log only prints the best
  match, so I couldn't check the gap. Logging the top two would make tuning easy.
- **More frames before pinging,** e.g. 3 of 4 instead of 2 of 3. That costs about 1 s and would have caught this case.
- **Enrol from call-like images:** "Choose photo" with a screenshot of the person on a WhatsApp video call.

## Issue 2: HANG UP needed 3 taps

During a video call, WhatsApp hides its buttons after a few seconds, so the end-call button isn't in the accessibility tree.
Taps 1 and 2 logged "Couldn't find the end-call button"; tap 3 worked, probably because the controls had reappeared.
Idea: if the button isn't found, send a tap to the middle of the call screen (`dispatchGesture`) to show the controls, wait
about 300 ms, then look again.

## Issue 3: 15 s to reply is too short

On call 2, Son had to unlock, read the prompt and do the fingerprint. He was too late, and the request expired.
`replySeconds = 15` is fixed in `FamilyLink`. Suggest **25–30 s** (or a setting, like the web app's slider), at least for the demo.

## Not tested yet
- Son taps **Yes**: green over WhatsApp
- **Daughter** calls: should ask Daughter's phone
- **Scam:** someone else holds up a photo/video of Son, Son taps No (the demo story; also shows whether a face on a screen matches)
- **Stranger** calls: nobody should be asked
- Late "No" after the timeout (your `41aee67` fix). Son's phone hides the buttons when the request expires, so it can only
  happen in the second or two between Mum's timeout and the expiry reaching Son
- Instagram / Messenger / Telegram

## Files
- Full detector log 02:22–02:28 (106 lines): [`2026-11-21-detector-log.txt`](2026-11-21-detector-log.txt), captured with `adb logcat -s AntiScamGuard`
