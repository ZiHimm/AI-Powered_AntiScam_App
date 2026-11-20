# Ben: cloud family relay (branch `ben/cloud-relay`)

## The problem it solves

Today all three phones must be on the same Wi-Fi as a laptop running the relay. If the laptop sleeps or the venue Wi-Fi
blocks phones from seeing each other, the demo dies. This change lets the relay run **on the internet**, so the phones
work from anywhere, even on mobile data.

## Files changed (7)

| File | What it is |
| --- | --- |
| `mobile/relay/server.js` | **The main change.** The relay now supports internet hosting safely (details below). |
| `mobile/www/monitor.js` | Small change to the monitor page: connects to the right family room, uses secure `wss://` on https, can be unlocked with a password. |
| `mobile/relay/check.js` | **New.** Test script: one command checks a relay (laptop or internet) is working and secure. |
| `mobile/relay/Dockerfile` | **New.** Packages the relay so a hosting service can run it. |
| `mobile/relay/fly.toml` | **New.** Fly.io settings: always on, Singapore server. |
| `mobile/relay/README.md` | **New.** How to use rooms, deploy, and test. |
| `.dockerignore` | **New.** Tells Docker which folders to leave out, so the upload stays small. |

## What the relay can do now

1. **Family rooms.** Each family gets a secret code in the address, e.g. `wss://<host>/f/K7Q2-9XPD-M4TR`. Families in
   different rooms never see each other. The code works like a password, so it's long and random
   (`node relay/server.js --new-code` makes one). The apps need no changes: you just type that address.
2. **Scammer console locked.** On the internet, the monitor's fake-alert buttons could be used to attack real families.
   They now only work with a secret password (`MONITOR_TOKEN`, opened as `monitor.html#token=…`). Without it, the
   monitor can only watch. The relay refuses to start on Fly/Railway without a token.
3. **Anti-abuse limits.** Per connection and per room, because a family often shares one IP:
   - Message size: 64 KB.
   - 20 messages a second per phone (bursts up to 60).
   - 240 new connections a minute per room; 12 connections in a room.
   - Loose per-IP caps on top (200 messages a second, 600 connections a minute).
   - Refusals close with a clear code: 4029 rate limited, 4008 room full, 4013 relay full.
4. **Crash fix.** One oversized message used to crash the whole relay. Now it only drops that one connection.
   (The original relay has the same gap.)
5. **Health check.** Opening `/healthz` shows whether the relay is up, so you can check before going on stage.
6. **Laptop mode unchanged.** `cd mobile && npm run relay` works exactly as before, so it's still the backup plan.
   Plain `/ws` (one shared room) stays on for the laptop but is switched off on internet hosts.

## What's not touched

- Jason's app (`android/`): no changes.
- Brian's phone app: only `monitor.js`, as the brief allowed.
- The web demo and `main`: no changes. It will go through a pull request.

## Status

- ✅ Built and tested on the laptop:
  - `node relay/check.js` (19 checks) passes in laptop mode, on a simulated internet host and in the Docker image;
  - 160 back-to-back reconnects from one IP are all accepted (no self-inflicted lockout);
  - the full "Son says No" flow works inside a family room (Mum: SCAM, Daughter: signed urgent alert);
  - a monitor without the token can't send anything;
  - a flood of 300 messages from one phone is cut to 60, other phones on the same IP unaffected.
- ❌ Not deployed to the internet yet. That needs a Fly.io (or Railway) account.
- ❌ Not yet tested on real phones over mobile data.

## Notes for the team

- **Jason:** Mum's phone needs detector APK `c158247` or newer (it turns `https://` into `wss://`). Then
  `https://<host>/f/<code>`, `wss://<host>/f/<code>` or `<host>/f/<code>` all work.
- **Brian:** `mobile/www/monitor.js` changed (room path, `wss://`, token, read-only state). Please run your 33-check
  browser test against the relay before merging.

## Next steps

1. Push `ben/cloud-relay`.
2. Deploy on Fly.io (steps in `mobile/relay/README.md`, keep `--ha=false` so there is exactly one server).
3. Test on real phones: Son/Daughter on mobile data, Mum on Wi-Fi, all on `wss://<host>/f/<code>`.
4. Open a pull request into `main` with the deployed address and test results.
