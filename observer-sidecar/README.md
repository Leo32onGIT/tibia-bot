# Observer sidecar

A small localhost service that bridges the Scala bot to CipSoft's Tibia Observer API
(`observer.tibia.com`). The JVM's TLS is Cloudflare-challenged there; a browser-grade
TLS client (`curl_cffi`) is not — so this owns that pass plus the Observer request
shapes, and the bot just calls it over `127.0.0.1`.

**Stateless.** The bot owns all durable state (the encrypted credential in
Postgres). The sidecar only translates one call, adds browser TLS, and returns JSON.

## Run

```
pip install -r requirements.txt
python sidecar.py            # binds 127.0.0.1:8787 by default
```

Environment:

| Var | Default | Purpose |
|-----|---------|---------|
| `OBSERVER_API_BASE_URL` | `https://observer.tibia.com/api/v1` | upstream |
| `OBSERVER_CLIENT_VERSION` | `1.1.6` | sent as `ClientVersion` |
| `OBSERVER_IMPERSONATE` | `chrome` | curl_cffi TLS profile |
| `OBSERVER_SIDECAR_TOKEN` | *(empty)* | if set, callers must send `X-Sidecar-Token` |
| `OBSERVER_SIDECAR_HOST` / `_PORT` | `127.0.0.1` / `8787` | bind address |

Keep it bound to loopback. The bot points at it via `observer-api.sidecar-url`
(and `sidecar-token`, if you set one).

## Endpoints

| Method | Path | Body | Returns |
|--------|------|------|---------|
| POST | `/link` | `{ accessToken, deviceIdentification, clientVersion? }` | `{ ok, status, credential, expires, accountLabel, accountCount, worlds }` |
| POST | `/renew` | `{ credential, deviceIdentification, clientVersion? }` | `{ ok, credential, expires }` |
| POST | `/ensure-rules` | `{ credential, worlds:[…], deviceIdentification? }` | `{ ok, worlds, skipped, limit, unchanged }` |
| POST | `/ensure-raid-rules` | `{ credential, worlds:[…], deviceIdentification? }` | `{ ok, worlds, skipped, limit, unchanged, unexplored?, regions, explored, areaNames }` |
| POST | `/explored-areas` | `{ credential }` | `{ ok, explored: {world: [areaId]}, areaNames: {areaId: name} }` |
| POST | `/clear-rules` | `{ credential, deviceIdentification? }` | `{ ok }` |
| POST | `/mwc` | `{ bearerToken }` | `{ ok, miniWorldChanges: [{world,title,body,…}] }` |
| POST | `/raids` | `{ bearerToken }` | `{ ok, raids: [{raidId,worldName,areaName,…}] }` |

The credential model (confirmed live):
- The `accessToken` (5-char code) is **single-use**. The account page shows it in
  upper case but the API only accepts it lower case, so `/link` lower-cases it.
- `/link` returns a **`credential`** — a ~90-day JWT bearer that *is* the durable
  credential (there is no separate refresh token). The bot stores this, encrypted.
  `expires` is its `exp` (epoch seconds).
- `/renew` presents the current credential to `Account/login` and gets a fresh
  90-day JWT — so renewing before expiry keeps the link alive until the user
  disconnects. Confirmed against a live credential.
- `/mwc` takes the credential as `bearerToken` and returns the **currently-active**
  MWC (`GET /MiniWorldChanges`), not the catalog.
- The Observer API is rule-driven: MWC only comes back for worlds the account has an
  **enabled** rule for. `/ensure-rules` sets enabled all-types rules (named
  "Violent Bot", in-app notifications only) for the given worlds, preserving every
  other world and category; `/clear-rules` removes just those on unlink.
- Both `ensure-` endpoints store only when the bot's rules would change, and say
  `unchanged` when they already were as asked. The bot reads `/explored-areas` on
  every raid check and sets the raid rules again only when an account has explored
  something new.
