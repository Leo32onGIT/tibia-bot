# Phase 0 spike — Observer API feasibility

Throwaway validation tool (not bot code). Confirms the Tibia Observer API path is
usable from a **server context** before any bot code is written. Authorised by
CipSoft on an as-is/unsupported basis.

```
pip install -r requirements.txt
python observer_probe.py                 # reachability + endpoint contracts (no token)
python observer_probe.py --token ABCDE   # full flow — WARNING: links the account (one-device)
```

## Findings (validated live)

| Question | Result |
|---|---|
| API base / version | `https://observer.tibia.com/api/v1` — ASP.NET Core (RFC 9110 ProblemDetails errors) |
| Cloudflare gate | Managed **challenge on TLS fingerprint**, not IP. A plain client (curl/JVM) is 403-challenged; a **browser-grade TLS** client passes clean (`cf-mitigated: None`). |
| How to pass, server-side | `curl_cffi` `impersonate="chrome"` → HTTP 200, no challenge, on a datacenter IP. No headless browser or JS-solver needed. |
| Health check | `GET /Status` — **unauthenticated**; returns `isAvailable`, `minimalClientVersion` (currently `1.1.5`), and client limits (`maximumMiniWorldChangeNotificationRules: 5`, etc.). |
| Login contract | `POST /Account/LoginWithAccessToken` requires `AccessToken` (5-char site token), `ClientVersion` (from Status), `DeviceIdentification` (label, e.g. "Violent Bot"), `AdditionalAccessTokens` (array, up to 3). Returns a bearer session. |
| MWC | `GET /MiniWorldChanges/GetMiniWorldChanges` → `401` without bearer (auth confirmed). |
| Raids (later) | `GET /Raids`, `GET /Raids/RaidTypeInformation` — same bearer, drop-in for the raids phase. |

**Token gotchas (confirmed live):** the 5-char token is **case-sensitive** (must be
sent exactly as the website shows it — do NOT upper/lower-case it) and **single-use**
(a successful `LoginWithAccessToken` consumes it; the JWT bearer it returns is what
you keep). A successful login **links the account to our device**, which can bump an
official-app link (one-device model).

A correct-case token returns `{"status":"success","bearerToken":"<JWT>", ...}`; a
wrong-case or spent one returns `{"status":"invalidAccessToken", ...}`.

**Full flow validated (live):** login (correct-case token) → JWT bearer →
`GET /MiniWorldChanges/GetMiniWorldChanges` → `200`.

- Login `success` returns: `bearerToken` (JWT), `accounts` (`[{loginDisplayValue,
  accountTitle, numCharacters, creationDate}]`) and `userSettings`
  (`appSetupCompleted`, `favouriteCharacters`, `newsNotificationRule{…}`).
- `GetMiniWorldChanges` returns the **MWC type catalog**: `[{id, name}]`, ids 0–30
  (Fury Gates, Chakoya Iceberg, … Spirit Gate Vengoth). This is the catalog that
  notification rules are built from — the *currently-active* MWC on a given world is
  a separate call, to be pinned down in the MWC-delivery phase.

**Session model:** because the 5-char token is single-use, the durable credential is
the session (JWT + a refresh via `Account/login`), which the sidecar must persist and
refresh. A user only re-adds a token if that session is lost.

## Architecture consequence

The bot's JVM HTTP client (pekko-http/JSSE) presents a Java TLS fingerprint and will
be Cloudflare-challenged. The Observer client therefore needs **browser-TLS
impersonation**. Recommended: a **small local sidecar** (this script's shape, as a
long-running localhost service) that owns the Cloudflare pass, the undocumented
endpoints, and the bearer lifecycle — the Scala bot calls it over localhost and stays
clean. This isolates the fragile/unsupported surface in one swappable place and serves
MWC now, raids later.
