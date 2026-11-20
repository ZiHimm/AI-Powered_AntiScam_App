# One app for the whole family: design (draft for Brian + Ben to agree)

**Author:** Jason · **Status:** Step 1 on `main`. **Step 2 built on branch `jason/step2`** (PR, not merged); QR join still to do.

## Why

Today there are two apps with fixed jobs. The detector (`android/`) protects **only Mum**, and Family Verify (`mobile/`)
lets **only Son and Daughter** be asked. So a deepfake of *Mum* calling Son, or of *Daughter* calling Son, is never caught,
and a family must install two different apps. Goal: **one app**. Every member is **protected** (their incoming video calls
are checked) and can be **asked** ("Are you calling Dad right now?"). Seniors also get **guardians**.

## Step 1: one APK, today's three roles (being built now, no protocol change)

The native app (`android/`, AntiScam Guard) asks **"Whose phone is this?"** on first launch: **Mum**, **Son** or
**Daughter**. It can be changed later in settings.

| Role | What the app does | Same as today's… |
| --- | --- | --- |
| Mum | Call watcher + face check + asks the family (unchanged) | detector |
| Son / Daughter | **Family mode** (new, native): relay connection in a foreground service; "Are you calling Mum?" pop-up → Yes/No → **fingerprint** (BiometricPrompt) → signed reply; guardian alerts (signature + nonce + freshness checked **before** anything is shown) with **Call Mum now** / signed **Tell Mum to hang up**; expired/cancelled prompts | Family Verify |

- **Same wire protocol as today**, so every mix works: native Son with Brian's Mum tab, Brian's Son app with the native
  Mum, and so on. Laptop relay and cloud relay both work.
- Same signing rules: one ECDSA P-256 key per phone in the Android Keystore, peers' keys pinned on first sight,
  Forget-keys button.
- Family Verify stays as the browser/test client and backup, and the monitor + scammer console are unchanged.

## Done early: the "Are you calling Mum?" question is now signed (works with both apps)
AntiScam Guard as Mum sends `{kind:"ping", req, env}`: `env` = `req` signed with Mum's key (same `{body, sig}` format as alerts).
- **AntiScam Guard family mode:** verifies `env` against Mum's pinned key and uses the signed body; a bad signature →
  "🛑 Fake question blocked"; no `env` (Family Verify as Mum) → shown with "⚠️ Not signed".
- **Family Verify (Brian):** works unchanged (ignores `env`). To do: when `env` is present, verify it with
  `verifyFrom(env, 'mum')` and use `JSON.parse(env.body)`; block on failure. Once both Mum apps sign, both member apps can
  reject unsigned questions.

## Step 2: everyone protected (built on `jason/step2`, needs review)

### What's built (and tested on two emulators + Family Verify)
- **Who are you?** First launch asks for your **name** (Mum, Dad, Aisha, 陈…), "I'm a senior citizen", and "Check my
  incoming video calls". The name becomes the member id (`Grandma Lim` → `grandma-lim`; non-Latin names get `m-<hash>`).
  Classic names (Mum/Son/Daughter) keep the old ids, so **Family Verify still works** with them.
- **Relay:** any member id `/^[a-z][a-z0-9-]{1,23}$/`, up to 8 per family (`4008 family full`); classic roles still listed.
- **Profiles:** signed `{kind:"profile"}` with name, senior, guardians, protect; sent when a member comes online.
- **Faces:** enrol your own face once (📷, "This is me"); it's sent signed to step-2 members
  (`model:"mobilefacenet-192"`), stored under your member id. Never sent to Family Verify (different model).
- **Every phone protects its owner** (if "check my calls" is on) and asks whoever the face matches:
  "Are you calling **<owner>** right now?", signed. Guardian alerts only if the owner is a **senior**, to their chosen guardians.
- **Every phone can be asked / be a guardian:** one inbox ("📬 Family questions & alerts"); each question/alert is checked
  against the key of the member who sent it.
- Tested: Dad (senior) ↔ Aisha both directions; faces shared both ways; SCAM + guardian alert; non-senior → no alerts;
  secret-question fallback; classic Son with Family Verify's Mum/Daughter.

### Original proposal (for reference)

### Members instead of fixed roles
- A family room has **members** with a stable id (random 8 hex, made on the phone) and a display name.
  `hello` becomes `{t:"hello", v:2, id, name, pub, senior, guardians:[ids], protect:true}`.
- **Relay (Ben):** slots keyed by `id`, not `mum|son|daughter`; up to 8 members per room; `peers` lists members as
  `{id:{name, online, pub, senior}}`. The 4000 "replaced" rule applies per id. v1 hellos (role names) keep working:
  treat the role as the id, so Step 1 phones and Family Verify still work during the switch.

### Messages (v2)
| Message | From → to | Body |
| --- | --- | --- |
| `ping` | callee's phone → impersonated member | `req:{type:"verify-request", v:2, callId, nonce, ts, expires, callee:<id>, calleeName, impersonated:<id>, prompt:"Are you calling <calleeName> right now?", senior}`, **signed** (env), fixing today's unsigned ping |
| `reply` | asked member → callee | signed `{type:"verify-reply", v:2, nonce, answer:"yes"\|"no", from:<id>, ts}` |
| `alert` | callee → callee's guardians | signed `{type:"guardian-alert", v:2, level, title, body, callee:<id>, about:<impersonated id>, callId, from:<callee id>, ts, nonce}` |
| `guardian-call` / `guardian-warn` | guardian → callee | as today, plus `to:<callee id>`; warn stays signed |
| `face` | member → every other member | signed `{type:"face", v:2, from:<id>, model:"mobilefacenet-192", embeddings:[[…192 floats]], ts}`. **Only accepted if `model` matches** (Family Verify's face-api 128-d vectors are a different model and can't be mixed) |
| `profile` | member → everyone | signed `{type:"profile", v:2, from, name, senior, guardians, protect}` |

### Rules
- **Who is asked:** the member whose shared face clearly matches the caller (current rule: ≤ 0.92, 0.05 gap, 2 of 3
  frames; switching faces triggers a new check, joining doesn't).
- **Who is alerted:** only if the *callee* is `senior`, to the callee's chosen `guardians`, never the impersonated person
  (they've already been asked).
- **Faces:** each member enrols **their own** face once on their own phone and sends it signed; others store it under
  that member's id. A new `face` from the same id replaces the old one. A member can delete theirs (sends an empty list).
- **Trust:** keys are pinned on first sight (as today). The **QR join** (Ben's stretch goal) carries the room address,
  room code and the creator's key fingerprint, so the first pin is verified instead of trust-on-first-use.

### Join flow (the original spec)
1. **Create family** (first person): makes the room code, shows a **QR code** + share link.
2. **Join family:** scan the QR / open the link → name → "I'm a senior citizen" toggle → pick guardians → enrol own face
   (3 selfies) → switch on protection (Accessibility + battery).
3. Everyone's phone shows the family list with 🟢/⚪ and who protects whom.

## Who does what (proposal)
| Who | Step 1 | Step 2 |
| --- | --- | --- |
| Jason | One APK with the role picker + native family mode (now) | Detector for any member, v2 messages, face sharing, profiles |
| Brian | Review family mode against Family Verify's behaviour; keep Family Verify as the test client | Native join flow + polish of the family screens; Family Verify v2 (or retire it to test-only) |
| Ben | No change | Relay v2 (member ids, 8 per room, v1 compatible) + QR join |

## Open questions
1. Do younger members get alerts about *their own* calls (non-senior)? Proposal: no. They see the result on their own screen only.
2. Should a member be able to be asked while their own call watcher is busy (they're on a call)? Proposal: yes; it's just a notification.
3. Family size limit: 8?
