# Observer sidecar

A small localhost service that bridges the Scala bot to CipSoft's Tibia Observer API
(`observer.tibia.com`). The JVM's TLS is Cloudflare-challenged there; a browser-grade
TLS client (`curl_cffi`) is not — so this owns that pass plus the Observer request
shapes, and the bot just calls it over `127.0.0.1`.

**Stateless.** The bot owns all durable state (the encrypted refresh token in
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
| GET | `/health` | — | `{ ok, minimalClientVersion }` |
| POST | `/link` | `{ accessToken, deviceIdentification, clientVersion? }` | `{ ok, status, bearerToken, refresh, expires, accountLabel, accountCount, raw }` |
| POST | `/refresh` | `{ refresh, deviceIdentification, clientVersion? }` | `{ ok, bearerToken, refresh, expires, raw }` |
| POST | `/mwc` | `{ bearerToken }` | `{ ok, status, miniWorldChanges }` *(Phase 3)* |

Notes:
- The `accessToken` (5-char code) is **case-sensitive and single-use** — sent verbatim.
- `/link`'s `refresh` is the durable credential the bot stores; `raw` is the full
  upstream response so a shape change is fixed here, in one place.
- `/refresh` presents the refresh token as the bearer to `Account/login`; the exact
  mechanism is **to be confirmed** on the first live refresh (see the code comment).
