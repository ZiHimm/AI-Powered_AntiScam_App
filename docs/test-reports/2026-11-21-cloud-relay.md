# Cloud relay test report (partial: Docker only, not deployed yet)

**Date:** 21 Nov 2026 · **Tested by:** Ben (with Claude) · **Code:** `main` at `3e184bf` (merge of `ben/cloud-relay`)

This covers steps 1–2 of the post-merge plan. The Fly.io deploy and the real-phone test over the internet (steps 3–4) are
**not done yet**. They're waiting on the team's OK to create a Fly account (it needs a card; see "Fly.io cost" below). This
report will be updated once they're done.

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

## Fly.io cost (for the team to decide)

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

- [ ] Team OK on the Fly account, then deploy with `--ha=false` and the token as a Fly secret.
- [ ] `/healthz` on the live host shows `monitorLocked: true`, and `check.js` passes against it.
- [ ] Real phones over the internet:
  - Son and Daughter on mobile data, Mum's detector (c158247+) on Wi-Fi, monitor on the laptop;
  - tests:
    - (a) Son taps No;
    - (b) a real WhatsApp call from Daughter;
    - (c) Son in airplane mode → offline in ~8 s → secret question;
    - (d) a read-only monitor can't inject;
  - timings compared with the laptop relay.

The live address, family code and token will be shared in the team chat only, never in this repo.
