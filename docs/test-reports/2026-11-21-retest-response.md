# Response to the retest (detector, Jason)

Great report, thanks. HANG UP one-tap, no wrong pings, the 30 s window and the stranger case all confirmed on real phones.

## Fixed (this commit)
| Issue | Fix | Checked how |
| --- | --- | --- |
| 2. **Frame error** at the screen edge (+ a 13 s stall once) | Off-by-one in the crop: with an odd-sized square at the edge it ended 1 px outside the screenshot. Now clamped by its corner. Screenshot failures (other than "too soon") are now logged: `Screenshot failed (code …)`, so a stall explains itself | Unit test: 200,000 random faces (at, past and bigger than every edge), plus the exact odd-size case from your log |
| 4. **Locked on the first face** | After the first check, if a *different* family member's face appears clearly (2 of 3 frames, ≤ 0.92, clear gap), that person is asked too: `The face changed: it was Daughter, now it looks like Son. Checking again.` A pending ping to the first person is cancelled on their phone. Each person at most once per call; a call already SCAM stays SCAM | Logic replay only (see below). **Needs a live test** |
| 5. **"Not family" flashed** 1 s before a match | "Not family" now needs **3 of 3** frames | Replay of your 14 calls: the flashes at 03:31:51 and 03:40:58 are gone; the real stranger (03:59) is still flagged, 1 s later |

Replay of all 14 calls from `2026-11-21-retest-logcat.txt` through the new rules: every "ask" decision is unchanged
(same person, same second). Only the two contradicting flashes disappear. The face-switch re-check would not have triggered
in these calls: in 03:31/03:40 the photo of Son never matched clearly (before the re-enrolment), and in 02:46 Daughter's face
only appeared after Son had already said No (SCAM stays SCAM).

## Not changed, on purpose
- **3. A photo of Son only just passes** (gap 0.05). Loosening the rule would bring back the wrong-person pings from the first
  test. For the demo: **enrol Son a second time from the same photo/video you'll hold up on stage** (section 2 → 🖼 next to Son),
  then check it with "Check a photo". A real deepfake of Son is built to look like Son, so it should match better than a
  printed photo held at an angle. Please note the distances you get.
- **Daughter's phone dropping off the relay in the background:** mobile/ (Brian).

## Please test (new APK after this commit; turn the call watcher off and on once)
1. **Face switch** (issue 4): Daughter's phone calls Mum with **Daughter's** face first → Daughter is asked (she can ignore
   it) → then hold up Son's photo/video (enrolled as above) → expect `The face changed … now it looks like Son` and a ping
   to **Son** → Son taps No → SCAM.
2. **Edge:** start a call with the face half off the screen (chin/cheek cut off). Expect no `Frame error` lines.
3. Look for any `Screenshot failed (code …)` lines and paste them if you see one.
