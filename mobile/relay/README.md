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
  keeping strangers out of a family, so don't make one up by hand. Codes need at least 8 letters/digits and are not
  case-sensitive.
- **Mum's phone (AntiScam Guard):** type the address starting with **`wss://`**. The app turns `https://` into `ws://`,
  which a hosted relay won't accept.
- **Monitor / scammer console:** `https://<host>/f/<FAMILY-CODE>/monitor.html#token=<MONITOR_TOKEN>`.
  Without the token (the part after `#` never reaches the server logs) the monitor is read-only: you can watch
  the traffic, but the presenter and scammer buttons send nothing.
- On a public host, plain `/ws` is switched off (it would be one room shared with every stranger). Set
  `ALLOW_DEFAULT_ROOM=1` to turn it back on.

### Limits

| Limit | Value |
| --- | --- |
| Message size | 64 KB (the biggest real message, a face embedding, is about 3 KB) |
| Connections per room | 12 (3 phones, monitors, reconnect overlap) |
| Rooms | 500 |
| Messages per IP | 20 per second, bursts up to 60 (extra messages are dropped) |
| New connections per IP | 30 per minute |

`GET /healthz` returns `{"ok":true, "rooms":…, "phones":…, "monitorLocked":true}`. Check it before going on stage.

## Deploy on Fly.io (recommended: no sleeping, Singapore region)

One-time setup: install `flyctl` (`iwr https://fly.io/install.ps1 -useb | iex` on Windows), then `fly auth signup`
or `fly auth login`. Fly asks for a card even on the free allowance.

From the **repo root**:

```bash
fly apps create family-verify-relay                       # pick another name if it's taken, and put it in fly.toml
fly secrets set MONITOR_TOKEN=<long random secret> --app family-verify-relay
fly deploy --config mobile/relay/fly.toml --dockerfile mobile/relay/Dockerfile --ha=false
```

- `--ha=false` matters: rooms live in memory, so there must be exactly **one** machine. Two machines would split a
  family across them.
- `fly.toml` keeps the machine always on (`auto_stop_machines = "off"`). Free tiers that sleep take ~50 s to wake,
  which would ruin the demo.
- The address is `wss://family-verify-relay.fly.dev/f/<FAMILY-CODE>`.
- Logs: `fly logs --app family-verify-relay`. Room codes are only logged as their first 4 characters.

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
node relay/check.js https://family-verify-relay.fly.dev <MONITOR_TOKEN>   # hosted
```

It uses throwaway room codes, so it never disturbs a real family. It checks:
- rooms are separate, and messages stay inside their room;
- the monitor token is enforced;
- a newer phone replaces an older one (close code 4000);
- short codes and oversized messages are refused, and the relay survives them;
- `/healthz` answers.
