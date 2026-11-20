# Update for Jason: Ben's next task + retest progress

## Ben (proposed; Ben hasn't confirmed yet)

- **`Ben-version` will not be merged.** It started from the first commit, and its Capacitor wrapper lives in `android/` and
  rewrites the root web demo, so merging it would overwrite your detector. Its only new idea (late-ping handling) is already
  in `main` (`838f91f` mobile, `41aee67` detector). Nothing on that branch blocks anything.
- **His new task: a cloud relay.** Host `mobile/relay/server.js` on the internet over `wss://`, so the phones don't depend on
  the laptop or the venue Wi-Fi. The laptop relay stays as the stage fallback, unchanged.
  - **Family rooms in the path:** `wss://<host>/f/<FAMILY-CODE>/ws`. Both apps already add `/ws` to whatever relay address
    is typed in, so **no change is needed in `android/`**: the user types `wss://<host>/f/<code>` in section 4.
  - **Same protocol** (hello / peers / msg / undeliverable / log; close code 4000; ping every 4 s; keys only for online
    phones). Your `FamilyLink.kt` shouldn't notice a difference.
  - **`inject` (scammer console/presenter) locked behind a token** on the internet. The monitor is read-only without it.
  - Host with WebSockets and no cold start (Fly.io/Railway), `/healthz`, limits per room/IP.
  - He works on a new branch (`ben/cloud-relay`) and opens a **pull request into `main`**. No direct pushes.
  - He's told **not to edit `android/`** and to report anything there to you.
- **Stretch (only after the relay works): QR-code pairing.** Mum's phone shows a QR code with its key fingerprint +
  family code, and Son/Daughter scan it instead of trust-on-first-use. This touches your pinning in `FamilyLink.kt`, so
  he's told to agree the design with you and Brian before starting.

### One thing for you (found while writing Ben's brief)
`FamilyLink.kt:94`: `r.replaceFirst(Regex("^https?://"), "ws://")` turns an **`https://`** relay address into **`ws://`**.
With a cloud relay that should be **`wss://`** (`https://` → `wss://`, `http://` → `ws://`). Laptop relay unaffected. Until
it's fixed, Ben's brief says to type `wss://…` on Mum's phone (that's kept as is).

## Retest of f2dc0f7 (in progress, 02:43–)

New APK installed over the old one (faces/keys kept), call watcher toggled off/on at 02:44:38, reply window shows 30 s.
Daughter's phone places the WhatsApp calls. Full report + logcat will go in `docs/test-reports/2026-11-21-retest.md`.

| Test | So far |
| --- | --- |
| R2 Son straight on, taps No | ✅ Son 0.67–0.89 (next: daughter 0.87–1.11) → Son asked (30 s) → signed No → SCAM → urgent alert. ❌ **HANG UP**: one tap at 02:46:32 logged `Couldn't find the end-call button; asked the user to tap it.` (no "after showing the call controls" line). Call ended by hand 12 s later. Will repeat with a 5 s wait |
| R4 Daughter's face | ✅ daughter 0.64–0.75 (next: mom/son 0.92–0.99) → **Daughter** asked; the prompt arrived even though that phone was placing the WhatsApp call; Yes → green; Son got the silent log |
| R1, R3/R7, R5, R6 | not run yet |
