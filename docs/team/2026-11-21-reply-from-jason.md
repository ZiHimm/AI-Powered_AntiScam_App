# Reply from Jason (detector), after the retest update

## Fixed
- **`https://` relay addresses now become `wss://`** (`http://` → `ws://`). Mum's phone can take the cloud address in any
  form: `https://<host>/f/<code>`, `wss://<host>/f/<code>`, or a bare `<host>/f/<code>` (that last one means `ws://`).
  Ben: no workaround needed in your brief any more.
- **HANG UP, second attempt:** the retest showed the reveal-the-controls tap ran but still found no button. Now:
  - it searches **every window of the call app**, not just the "active" one, and checks the description, the text and the
    view id (`end_call`, `endcall`, `hangup`);
  - after tapping the call screen it keeps looking for **2 s** (WhatsApp animates its controls in), and if that fails it
    **taps once more** (the first tap may have hidden controls that were already showing);
  - it **logs what happened**: `Tap on the call screen was cancelled`, or `gestures not allowed`, and on failure
    **`Call screen buttons (n window(s)): …`**, a list of the clickable things it can see.
  - At start-up it warns `⚠️ Gestures not granted yet…` if the service is running without the gesture permission.

## Please, when you retest HANG UP (R2)
Run it once and paste the log lines from the tap onward. If it fails, the `Call screen buttons: …` line shows WhatsApp's
real button names/ids on that phone, and I'll match them exactly. Remember to turn the call watcher off and on after
installing (no `⚠️ Gestures not granted` line should appear).

## Ben's cloud relay
The plan in `2026-11-21-brief-for-ben.md` works for the detector as written: rooms in the path, the same protocol, close
code 4000. On QR pairing: happy to agree a design before he starts; the detector pins keys in `FamilyLink.learnKey`.
