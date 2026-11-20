# Cloud relay test report (partial: relay live, phone test pending)

**Date:** 21 Nov 2026 · **Tested by:** Ben (with Claude) · **Code:** `main` at `3e184bf` (merge of `ben/cloud-relay`)

This covers steps 1–3 of the post-merge plan:
- the Docker test;
- the deploy on Fly.io;
- the switch to a free **laptop + Docker + Cloudflare tunnel** relay, which is what we use for the demo.

The real-phone test over the internet (step 4) is **not done yet**; this report will be updated after it.

## Which relay we use: laptop + Docker + Cloudflare tunnel

Fly.io worked (below), but it isn't usable without a card. Trial machines stop every 5 minutes, and the logs show it
stopping at 18:34 and 18:42 and waking on the next request. That would drop every phone mid-demo. Adding a card wasn't
an option for us, so the relay runs in Docker on Ben's laptop, behind a free Cloudflare quick tunnel:

```
Phones (Wi-Fi or mobile data) → Cloudflare (public https:// address) → laptop (Docker relay)
```

| What | Value |
| --- | --- |
| Relay | `family-verify-relay` image from this branch, `PUBLIC=1`, monitor token set |
| Tunnel | `cloudflare/cloudflared` quick tunnel, random `*.trycloudflare.com` address (shared in team chat only) |
| Cost | free, with no account and no card |
| Catch | the laptop must stay on, awake and online. The address changes if the tunnel restarts |

**New `PUBLIC=1` switch** (`server.js`, one line). Through a tunnel, the laptop relay is on the internet, but it
didn't know that. It still opened the shared `/ws` room to anyone who found the address, and it would have started
without a token. With `PUBLIC=1` it gets the same lockdown as Fly or Railway, and it refuses to start without a token.

| Check through the tunnel | Result |
| --- | --- |
| `GET /healthz` | ✅ `{"ok":true, …, "monitorLocked":true}` |
| `node mobile/relay/check.js https://<tunnel> <token>` | ✅ **all 20 checks passed**, including "plain /ws is refused" |
| Same `check.js` against the laptop relay without `PUBLIC` | ✅ all 20 passed, `/ws` default room still works |
| Relay started with `PUBLIC=1` and no token | ✅ refuses to start |
| Monitor page through the tunnel, with the token | ✅ "Relay connected" |


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

## Fly.io (deployed, but trial only)

| What | Value |
| --- | --- |
| App | `antiscam-family-relay`, address `https://antiscam-family-relay.fly.dev` |
| Machines | 1 (`--ha=false`), shared-cpu-1x 256 MB, region `sin` (Singapore). Trial account: stops every 5 min |
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

Expected cost with a card: about $0, because the relay comes to roughly $3/month and that's under the $5 waiver. We
didn't add a card, so the app stays on the trial and costs nothing. If someone on the team adds a card later, the Fly
relay is ready as is: it's always on and doesn't need the laptop.

## Not done yet

- [x] Deploy on Fly.io with `--ha=false` and the token as a Fly secret (trial only, see above).
- [x] Free relay for the demo: laptop + Docker + Cloudflare tunnel, `PUBLIC=1`, all 20 checks pass through the tunnel.
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
