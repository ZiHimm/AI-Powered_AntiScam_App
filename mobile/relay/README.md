# Family relay: laptop or internet

The relay passes small signed messages between Mum's, Son's and Daughter's phones. It never sees faces or video,
and it vouches for nothing: the phones check every signature themselves.

## On the laptop (stage fallback, unchanged)

```bash
cd mobile && npm run relay
```

Phones type `ws://<laptop IP>:8000`, as before. Plain `/ws` is the default room, and the scammer console works
without a token on the laptop.

## On the internet: family rooms

Each family gets its own room in the address:

```
wss://<host>/f/<FAMILY-CODE>
```

Type exactly that as the relay address on all three phones (the apps add `/ws` themselves). Each code is a separate
room with its own mum/son/daughter slots, so families never see each other.

- **Make a code:** `node relay/server.js --new-code` prints one like `K7Q2-9XPD-M4TR`. The code is the only thing
  keeping strangers out of a family, so don't make one up by hand. Codes need at least 8 letters/digits. Case and
  hyphens are ignored (`K7Q2-9XPD-M4TR` and `k7q29xpdm4tr` are the same room), so a typo can't split a family.
- **Mum's phone (AntiScam Guard):** use detector APK `c158247` or newer. Any of `https://<host>/f/<code>`,
  `wss://<host>/f/<code>` or `<host>/f/<code>` works.
- **Monitor / scammer console:** `https://<host>/f/<FAMILY-CODE>/monitor.html#token=<MONITOR_TOKEN>`.
  Without the token (the part after `#` never reaches the server logs) the monitor is read-only: you can watch
  the traffic, but the presenter and scammer buttons send nothing.
- On a public host (Fly, Railway, or any relay started with `PUBLIC=1`), plain `/ws` is switched off, because it
  would be one room shared with every stranger. Set `ALLOW_DEFAULT_ROOM=1` to turn it back on.

### Limits

A whole family (and the laptop) often shares one public IP (hotspot, venue Wi-Fi, carrier NAT on mobile data), so
the real limits are per connection and per room. Per IP is only a loose global cap.

| Limit | Value | When it's hit |
| --- | --- | --- |
| Message size | 64 KB (a face embedding, the biggest real message, is ~3 KB) | connection closed with 1009 |
| Messages per connection | 20/s, bursts up to 60 | extra messages dropped; the phone gets `{t:"error", code:4029}` |
| Messages per IP | 200/s, bursts up to 400 | same |
| New connections per room | 240 per minute (every device in a 1.5 s reconnect loop is ~150) | closed with **4029** "rate limited" |
| New connections per IP | 600 per minute | closed with **4029** "rate limited" |
| Connections in a room | 12 (3 phones, monitors, reconnect overlap) | closed with **4008** "room full" |
| Rooms | 500 | closed with **4013** "relay full" |

The per-IP caps read the client address from `fly-client-ip` on Fly.io, which the client can't fake. On Railway they
fall back to the first `x-forwarded-for` address, which a client *can* fake, so there the per-IP caps can be bypassed
(the per-connection and per-room limits still hold).

Only accepted connections count towards the connection limits, so refused reconnects can't keep a family locked out.
None of these use 4000 (both apps stop reconnecting on 4000; it only means "replaced by a newer connection").

`GET /healthz` returns `{"ok":true, "rooms":…, "phones":…, "monitorLocked":true}`. Check it before going on stage.

## Free: laptop + Docker + Cloudflare tunnel (what we use for the demo)

The relay runs in Docker on the laptop, and a free Cloudflare quick tunnel gives it a public `https://` address. That
means phones on mobile data can reach it too. No account or card is needed. The laptop must stay on, awake and online.

From the **repo root** (Docker Desktop running):

```bash
docker build -f mobile/relay/Dockerfile -t family-verify-relay .
docker run -d --name family-relay --restart unless-stopped -p 8000:8000   -e MONITOR_TOKEN=<long random secret> -e PUBLIC=1 family-verify-relay
docker run -d --name family-tunnel --restart unless-stopped cloudflare/cloudflared:latest   tunnel --no-autoupdate --url http://host.docker.internal:8000
docker logs family-tunnel        # shows the address: https://<random words>.trycloudflare.com
```

- **Keep `PUBLIC=1`:** the tunnel puts the laptop on the internet, so this gives it the same lockdown as a cloud host
  (family codes only, and a token is required).
- **Addresses:** phones use `wss://<random words>.trycloudflare.com/f/<FAMILY-CODE>` (Mum can use `https://`). The
  monitor is the same address plus `/monitor.html#token=…`.
- **The address changes** whenever the tunnel container restarts (for example after a reboot). Run
  `docker logs family-tunnel` again and update the phones.
- **Stop and start:** `docker stop family-relay family-tunnel` / `docker start family-relay family-tunnel`.
- **Sleep:** set Windows sleep to Never while plugged in. A sleeping laptop drops every phone.

## Deploy on Fly.io (no sleeping, Singapore region; needs a card)

One-time setup: install `flyctl` (`iwr https://fly.io/install.ps1 -useb | iex` on Windows), then `fly auth signup`
or `fly auth login`. Without a card, trial machines stop every 5 minutes, which drops the phones, so Fly needs a
card to be usable.

From the **repo root**:

```bash
fly apps create antiscam-family-relay   # names containing "verify" are blocked; another name must go in fly.toml
fly secrets set MONITOR_TOKEN=<long random secret> --app antiscam-family-relay
fly deploy --config mobile/relay/fly.toml --dockerfile mobile/relay/Dockerfile --ha=false
```

- `--ha=false` matters: rooms live in memory, so there must be exactly **one** machine. Two machines would split a
  family across them.
- `fly.toml` keeps the machine always on (`auto_stop_machines = "off"`). Free tiers that sleep take ~50 s to wake,
  which would ruin the demo.
- The address is `wss://antiscam-family-relay.fly.dev/f/<FAMILY-CODE>`.
- Logs: `fly logs --app antiscam-family-relay`. Room codes are only logged as their first 4 characters.

## Deploy on Railway (alternative)

1. New project → Deploy from GitHub repo → this repo.
2. Service → Settings: root directory `/`, and add the variable `RAILWAY_DOCKERFILE_PATH=mobile/relay/Dockerfile`.
3. Variables: `MONITOR_TOKEN=<long random secret>`. Keep **1 replica** and leave "serverless" (sleeping) off.
4. Settings → Networking → Generate domain. The address is `wss://<domain>/f/<FAMILY-CODE>`.

The relay refuses to start on Fly or Railway without `MONITOR_TOKEN`.

## Check a running relay

```bash
cd mobile
node relay/check.js http://localhost:8000                          # laptop
node relay/check.js https://antiscam-family-relay.fly.dev <MONITOR_TOKEN>   # hosted
```

It uses throwaway room codes, so it never disturbs a real family. It checks:
- rooms are separate, and messages stay inside their room;
- the monitor token is enforced;
- a newer phone replaces an older one (close code 4000);
- a Mum in another room never replaces this room's Mum;
- a flood from one phone is cut without affecting other phones on the same IP;
- a full room refuses with close code 4008;
- short codes and oversized messages are refused, and the relay survives them;
- `/healthz` answers.
