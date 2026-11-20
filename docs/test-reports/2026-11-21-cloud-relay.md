# Cloud relay test report (partial: deployed, phone test pending)

**Date:** 21 Nov 2026 · **Tested by:** Ben (with Claude) · **Code:** `main` at `3e184bf` (merge of `ben/cloud-relay`)

This covers steps 1–3 of the post-merge plan: the Docker test, and the deploy on Fly.io with checks against the live relay.
The real-phone test over the internet (step 4) is **not done yet**; this report will be updated after it.

## Setup

| What | Value |
| --- | --- |
| Laptop | Windows 11, Docker 29.4.0 |
| Image | built from the repo root with the team's exact commands (below) |
| Monitor token | a test value, used only on this laptop |
| Room codes | throwaway test codes. No real family code was used or recorded |

```bash
docker build -f mobile/relay/Dockerfile -t family-verify-relay .
docker run -p 8000:8000 -e MONITOR_TOKEN=<test token> family-verify-relay
node mobile/relay/check.js http://localhost:8000 <test token>
```

## Results

| Test | Result |
| --- | --- |
| Image builds from `main` | ✅ |
| Relay starts in the container with a token | ✅ |
| `node mobile/relay/check.js` | ✅ **all 20 checks passed** |
| `GET /healthz` | ✅ `{"ok":true, "rooms":…, "phones":0, "monitorLocked":true}` |
| `/f/<code>/monitor.html#token=…` in a browser | ✅ page loads inside the room and shows "Relay connected" |

`check.js` covers:
- room isolation, and undeliverable messages;
- a monitor without the token is read-only, and one with the token can inject;
- 4000 replace inside a room only;
- a flood from one phone is cut to about 60 messages, with other phones on the same IP unaffected;
- 4008 when a room is full;
- code case and hyphens are ignored, and short codes are refused;
- 1009 for an oversized message, and the relay survives it.

## Deployed on Fly.io

| What | Value |
| --- | --- |
| App | `antiscam-family-relay`, address `https://antiscam-family-relay.fly.dev` |
| Machines | 1 (`--ha=false`), shared-cpu-1x 256 MB, region `sin` (Singapore), always on |
| IP | shared IPv4 + IPv6 (free); no dedicated IPv4 |
| Monitor token | set as a Fly secret, never in git |
| Family code | made with `node mobile/relay/server.js --new-code` and shared in team chat only |

The app name changed: Fly refuses any name containing "verify" (its phishing filter), so it's `antiscam-family-relay`.
`fly.toml` and the relay README now use that name.

| Live check | Result |
| --- | --- |
| `GET /healthz` | ✅ `{"ok":true, "rooms":0, "phones":0, "monitorLocked":true}` |
| `fly status` | ✅ 1 machine in `sin`, started, health check passing |
| `node mobile/relay/check.js https://antiscam-family-relay.fly.dev <token>` | ✅ **all 20 checks passed** (after the fix below) |
| Same `check.js` against the laptop relay | ✅ all 20 checks passed (laptop mode unchanged) |

**Fly's proxy delays the "closed" event by ~5 s.** On the first live run, 3 checks failed:
- 4000 replace;
- 4008 room full;
- 1009 oversize.

The relay was right: a probe showed the close code is sent at once. Fly's proxy keeps the connection open for about
5 s afterwards, though, and the client only reports "closed" once it really ends. Browsers behave the same.

What this means for the phones:
- a replaced phone is already out of the room, and only learns it ~5 s later;
- a phone refused for "room full" or "rate limited" waits ~5 s before retrying, which is harmless.

`check.js` used fixed 300–400 ms waits. It now waits for the close event, up to 10 s.

## Fly.io cost

Checked on 21 Nov 2026 against Fly's own docs:
- [pricing](https://docs.fly.io/about/pricing)
- [free trial](https://docs.fly.io/about/free-trial)
- [bill waiver](https://docs.fly.io/about/free-waiver)

| Item | Cost |
| --- | --- |
| Always-on shared-cpu-1x, 256 MB | $2.19/month in the cheapest (US) regions. Singapore is a bit higher; Fly publishes no fixed figure (check the calculator) |
| Outbound data, Asia | $0.04/GB. The relay sends small JSON messages, so this is negligible |
| Shared IPv4 + IPv6 | free (enough for the relay) |
| Monthly bill under $5 | waived (personal account, card on file) |
| Free trial | $1 credit for 7 days, no card. Trial machines stop after 5 minutes, so it can't host an always-on relay |

Expected cost: about $0, because the relay comes to roughly $3/month and that's under the $5 waiver. A card is still
required, and there's no spending cap, so delete the app after the hackathon.

## Not done yet

- [x] Deploy on Fly.io with `--ha=false` and the token as a Fly secret.
- [x] `/healthz` on the live host shows `monitorLocked: true`, and `check.js` passes against it.
- [ ] Real phones over the internet:
  - Son and Daughter on mobile data, Mum's detector (c158247+) on Wi-Fi, monitor on the laptop;
  - tests:
    - (a) Son taps No;
    - (b) a real WhatsApp call from Daughter;
    - (c) Son in airplane mode → offline in ~8 s → secret question;
    - (d) a read-only monitor can't inject;
  - timings compared with the laptop relay.

The live address, family code and token will be shared in the team chat only, never in this repo.
