#!/usr/bin/env python3
"""Observer sidecar — the bot's bridge to CipSoft's Tibia Observer API.

The JVM's TLS is Cloudflare-challenged on observer.tibia.com; a browser-grade TLS
client is not. This tiny localhost service owns that pass (via curl_cffi) plus the
Observer request shapes, so the Scala bot just calls it over 127.0.0.1 and stays
clean. Stateless: the bot owns all durable state (the encrypted refresh token in
Postgres); this only translates a call, adds browser TLS, and hands the JSON back.

Endpoints (all JSON):
  GET  /health                      -> { ok, minimalClientVersion }
  POST /link    { accessToken, deviceIdentification[, clientVersion] }
                                     -> { ok, status, bearerToken, refresh, expires,
                                          accountLabel, accountCount, raw }
  POST /refresh { refresh, deviceIdentification[, clientVersion] }
                                     -> { ok, bearerToken, refresh, expires, raw }
  POST /mwc     { bearerToken }      -> { ok, status, miniWorldChanges }   (Phase 3)

Run:  pip install -r requirements.txt ; python sidecar.py
Env:  OBSERVER_API_BASE_URL (default https://observer.tibia.com/api/v1)
      OBSERVER_CLIENT_VERSION (default 1.1.6)
      OBSERVER_IMPERSONATE   (default chrome)
      OBSERVER_SIDECAR_TOKEN (optional shared secret; if set, callers must send
                              it as the X-Sidecar-Token header)
      OBSERVER_SIDECAR_HOST / OBSERVER_SIDECAR_PORT (default 127.0.0.1 / 8787)
      OBSERVER_RAID_REGIONS  (explored | all; default explored) what a raid rule covers
"""
import base64
import json
import os
import uuid

from curl_cffi import requests as cr
from flask import Flask, jsonify, request

BASE = os.environ.get("OBSERVER_API_BASE_URL", "https://observer.tibia.com/api/v1").rstrip("/")
CLIENT_VERSION = os.environ.get("OBSERVER_CLIENT_VERSION", "1.1.6")
IMPERSONATE = os.environ.get("OBSERVER_IMPERSONATE", "chrome")
SHARED_TOKEN = os.environ.get("OBSERVER_SIDECAR_TOKEN", "")
HOST = os.environ.get("OBSERVER_SIDECAR_HOST", "127.0.0.1")
PORT = int(os.environ.get("OBSERVER_SIDECAR_PORT", "8787"))

app = Flask(__name__)


def _observer(method, path, bearer=None, body=None):
    headers = {"Accept": "application/json"}
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    if body is not None:
        headers["Content-Type"] = "application/json"
    return cr.request(
        method, f"{BASE}{path}", headers=headers,
        data=json.dumps(body) if body is not None else None,
        impersonate=IMPERSONATE, timeout=20,
    )


def _authorised(req) -> bool:
    return not SHARED_TOKEN or req.headers.get("X-Sidecar-Token") == SHARED_TOKEN


def _jwt_exp(token):
    """The `exp` (epoch seconds) from a JWT bearer, or None. The bearer IS the
    durable credential (≈90-day lifetime); this is when it needs renewing."""
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload)).get("exp")
    except Exception:  # noqa: BLE001
        return None


def _json_body():
    return request.get_json(force=True, silent=True) or {}


@app.before_request
def _guard():
    if request.path == "/health":
        return None
    if not _authorised(request):
        return jsonify({"ok": False, "error": "unauthorised"}), 401
    return None


@app.get("/health")
def health():
    try:
        r = _observer("GET", "/Status")
        if r.status_code == 200:
            d = r.json()
            return jsonify({"ok": True, "minimalClientVersion": d.get("minimalClientVersion")})
        return jsonify({"ok": False, "status_code": r.status_code}), 502
    except Exception as exc:  # noqa: BLE001 — surfaced to the caller, not swallowed
        return jsonify({"ok": False, "error": str(exc)}), 502


@app.post("/link")
def link():
    b = _json_body()
    # The account page presents the token in upper case, but the API only accepts it
    # lower case — so normalise it here regardless of how the user typed it.
    token = (b.get("accessToken") or "").strip().lower()
    device = b.get("deviceIdentification") or "Violent Bot"
    client_version = b.get("clientVersion") or CLIENT_VERSION
    if not token:
        return jsonify({"ok": False, "error": "accessToken required"}), 400
    try:
        r = _observer("POST", "/Account/LoginWithAccessToken", body={
            "AccessToken": token,
            "ClientVersion": client_version,
            "DeviceIdentification": device,
            "AdditionalAccessTokens": [],
        })
        d = r.json()
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 502
    status = d.get("status")
    accounts = d.get("accounts") or []
    bearer = d.get("bearerToken")              # the JWT IS the durable credential (~90d)
    worlds = []
    if status == "success" and bearer:
        # The account's distinct character-worlds — what the bot sets MWC rules for.
        try:
            cr_resp = _observer("GET", "/Account/characters", bearer=bearer)
            if cr_resp.status_code == 200:
                worlds = sorted({c.get("world") for c in cr_resp.json() if c.get("world")})
        except Exception:  # noqa: BLE001 — worlds are best-effort; link still succeeds
            worlds = []
    return jsonify({
        "ok": status == "success",
        "status": status,                      # success | invalidAccessToken | ...
        "credential": bearer,                  # what the bot stores (encrypted)
        "expires": _jwt_exp(bearer) if bearer else None,
        "accountLabel": (accounts[0].get("accountTitle") if accounts else None),
        "accountCount": len(accounts),
        "worlds": worlds,
        "raw": d,                              # full shape, so the bot can adapt if a field moves
    })


@app.post("/renew")
def renew():
    """Renew the credential before its ~90-day expiry.

    Confirmed live: `Account/login` with the current JWT in the Authorization header
    (plus client/device in the body) returns a fresh 90-day JWT. So the durable
    credential renews itself indefinitely as long as we call this before it lapses.
    """
    b = _json_body()
    credential = b.get("credential")
    device = b.get("deviceIdentification") or "Violent Bot"
    client_version = b.get("clientVersion") or CLIENT_VERSION
    if not credential:
        return jsonify({"ok": False, "error": "credential required"}), 400
    try:
        r = _observer("POST", "/Account/login", bearer=credential, body={
            "ClientVersion": client_version,
            "DeviceIdentification": device,
        })
        d = r.json() if r.text else {}
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 502
    new_bearer = d.get("bearerToken")
    return jsonify({
        "ok": r.status_code == 200 and bool(new_bearer),
        "status_code": r.status_code,
        "credential": new_bearer,
        "expires": _jwt_exp(new_bearer) if new_bearer else None,
        "raw": d,
    })


def _current_settings(credential, device):
    r = _observer("POST", "/Account/login", bearer=credential,
                  body={"ClientVersion": CLIENT_VERSION, "DeviceIdentification": device})
    return r.json().get("userSettings", {}) if r.status_code == 200 else None


def _catalog_ids(credential):
    r = _observer("GET", "/MiniWorldChanges/GetMiniWorldChanges", bearer=credential)
    return [c["id"] for c in r.json()] if r.status_code == 200 else []


RULE_NAME = "Violent Bot"

# The limits /Status reported on 24 Sep 2026: 5 mini world change rules and 15 raid
# rules per account. Used only when /Status can't be read.
DEFAULT_LIMITS = {"maximumMiniWorldChangeNotificationRules": 5, "maximumRaidNotificationRules": 15}


def _rule_limit(key):
    """How many rules of one kind an account may hold, from the unauthenticated
    /Status. A settings store with more is rejected whole, so every rule is lost."""
    try:
        r = _observer("GET", "/Status")
        if r.status_code == 200:
            value = (r.json().get("dynamicClientSettings") or {}).get(key)
            if isinstance(value, int) and value > 0:
                return value
    except Exception:  # noqa: BLE001 — fall back to the last known limit
        pass
    return DEFAULT_LIMITS[key]


def _stored(r, applied, skipped, limit, unexplored=None, extra=None):
    """The answer to a settings store: which worlds got a rule, which were left out
    for want of room, which (raids only) had nothing explored to cover, and the
    upstream's own words when it refused. `r` is None when the rules were already
    as asked and nothing was stored (`unchanged`). `extra` is added as it is."""
    status = 200 if r is None else r.status_code
    ok = status == 200
    out = {"ok": ok, "status_code": status, "worlds": applied, "skipped": skipped, "limit": limit,
           "unchanged": r is None}
    if unexplored:
        out["unexplored"] = unexplored
    if extra:
        out.update(extra)
    if not ok:
        out["error"] = (r.text or "")[:300]
    return jsonify(out)


def _field(rule, *keys):
    """A rule's field under whichever spelling the settings came back with."""
    for key in keys:
        if key in rule:
            return rule[key]
    return None


def _same_raid_rules(before, after):
    """Whether the bot's raid rules on the account are already `after`: the same
    worlds, regions, modes and a real rule id each. Anything unreadable counts as
    different, so the worst a changed shape does is store as before."""
    def shape(r):
        return ((_field(r, "World", "world") or "").lower(),
                tuple(sorted(_field(r, "RegionIds", "regionIds") or [])),
                bool(_field(r, "isEnabled", "IsEnabled")),
                _field(r, "areaRevealed", "AreaRevealed"), _field(r, "subareaRevealed", "SubareaRevealed"),
                _field(r, "raidStarted", "RaidStarted"))
    if any(_field(r, "ruleId", "RuleId") in (None, NIL_RULE_ID) for r in before):
        return False
    return sorted(map(shape, before)) == sorted(map(shape, after))


def _same_mwc_rules(before, after):
    """Whether the bot's mini world change rules on the account are already
    `after`, as [[_same_raid_rules]] does for raids."""
    def shape(r):
        return ((_field(r, "World", "world") or "").lower(),
                tuple(sorted(_field(r, "miniWorldChanges", "MiniWorldChanges") or [])),
                bool(_field(r, "isEnabled", "IsEnabled")),
                _field(r, "notifications", "Notifications"))
    return sorted(map(shape, before)) == sorted(map(shape, after))


def _explored_by_world(areas):
    """`/Area/ExploredAreas` — [{world, exploredAreas: [{areaId, name}]}] — as
    ({world: sorted ids}, {id: name}, sorted field names of an area). A world with
    nothing explored is left out."""
    by_world, names, fields = {}, {}, set()
    for entry in areas or []:
        ids = set()
        for area in entry.get("exploredAreas") or []:
            fields.update(area.keys())
            if area.get("areaId") is None:
                continue
            ids.add(area["areaId"])
            name = area.get("areaName") or area.get("name")
            if name:
                names[str(area["areaId"])] = name
        if ids and entry.get("world"):
            by_world[entry["world"]] = sorted(ids)
    return by_world, names, sorted(fields)


@app.post("/ensure-rules")
def ensure_rules():
    """Ensure an enabled MWC rule (all types) exists for as many requested worlds
    as the account has room for.

    The bot owns the MWC rules named "Violent Bot" and replaces all of them; every
    other rule, and every other notification category, is kept untouched. The API
    caps an account's MWC rules (`maximumMiniWorldChangeNotificationRules` in
    /Status) and rejects a store over it outright, so `worlds` is taken in the
    order given — the bot sends them most wanted first — up to the room the
    account's own rules leave. `notifications` is in-app-only (no push), since the
    bot polls rather than receiving pushes.
    """
    b = _json_body()
    credential = b.get("credential")
    worlds = b.get("worlds") or []
    device = b.get("deviceIdentification") or "Violent Bot"
    if not credential or not worlds:
        return jsonify({"ok": False, "error": "credential and worlds required"}), 400
    try:
        settings = _current_settings(credential, device)
        if settings is None:
            return jsonify({"ok": False, "error": "could not read settings"}), 502
        ids = _catalog_ids(credential)
        limit = _rule_limit("maximumMiniWorldChangeNotificationRules")
        existing = settings.get("miniWorldChangeNotificationRules") or []
        kept = [r for r in existing if r.get("ruleName") != RULE_NAME]
        room = max(0, limit - len(kept))
        applied, skipped = worlds[:room], worlds[room:]
        managed = [{"ruleName": RULE_NAME, "World": w, "miniWorldChanges": ids,
                    "isEnabled": True, "notifications": "appNotifications"} for w in applied]
        # Stored only when they differ from the bot's rules already on the account.
        before = [r for r in existing if r.get("ruleName") == RULE_NAME]
        r = None if _same_mwc_rules(before, managed) else \
            _observer("POST", "/Settings/StoreUserSettings", bearer=credential,
                      body={**settings, "miniWorldChangeNotificationRules": kept + managed})
        return _stored(r, applied, skipped, limit)
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 502


# Every region, for OBSERVER_RAID_REGIONS=all. The API has no list of regions, and it does not
# check a rule's RegionIds against the account's exploration or even against the
# regions that exist: on 24 Sep 2026 it stored ids 1-60 for an account that had
# explored only 3 (Carlin) and 23 (Hrodmir). Region ids are small integers, so this
# range covers them all, and an explored id outside it is added anyway.
ALL_REGION_IDS = list(range(1, 61))

# Which regions a raid rule covers: "explored" (the default) or "all". A rule over
# regions 1-60 was stored without complaint but never reported a single raid, not
# even one in an explored area (25 Sep 2026), so rules are back to what the
# account has explored, the shape the Observer app itself makes. "all" is kept to
# test widening again once raids are seen arriving.
RAID_REGIONS = os.environ.get("OBSERVER_RAID_REGIONS", "explored").strip().lower()

# The id Observer stored for rules sent without one. Its own app gives each rule a
# real id, and a rule with this one may never be checked against raids.
NIL_RULE_ID = "00000000-0000-0000-0000-000000000000"


@app.post("/ensure-raid-rules")
def ensure_raid_rules():
    """Ensure an enabled raid rule on each of `worlds`, as many worlds as the
    account has room for.

    A rule covers the regions the account has explored there, or every region
    (ALL_REGION_IDS) with OBSERVER_RAID_REGIONS=all; see RAID_REGIONS for why the
    default went back to explored. Each rule carries a real id (see NIL_RULE_ID).
    A world with nothing explored gets no rule and is reported as `unexplored`.
    `worlds` is exactly what gets a rule, most wanted first: the bot sends only worlds the
    account has characters on that some guild tracks, the linking guild's first, and
    nothing else is added here. An empty list leaves the account with none of the bot's raid
    rules. All three modes (area/subarea revealed, raid started) are on, so the
    /Raids feed carries every stage; the bot filters by `category`. Like MWC
    rules, raid rules are capped (`maximumRaidNotificationRules`), so worlds are
    taken in order up to the room the account's own rules leave.

    The answer also says what each rule covers: `regions` (world -> region ids),
    `areaNames` (id -> name, where the explored areas carry one) and `areaFields`.
    """
    b = _json_body()
    credential = b.get("credential")
    requested = b.get("worlds") or []
    device = b.get("deviceIdentification") or "Violent Bot"
    if not credential:
        return jsonify({"ok": False, "error": "credential required"}), 400
    try:
        settings = _current_settings(credential, device)
        if settings is None:
            return jsonify({"ok": False, "error": "could not read settings"}), 502
        explored = _observer("GET", "/Area/ExploredAreas", bearer=credential)
        areas = explored.json() if explored.status_code == 200 else []
        # What each world's rule covers; explored worlds don't earn a rule of
        # their own.
        explored_ids, names, fields = _explored_by_world(areas)
        explored_by_world = {w.lower(): ids for w, ids in explored_ids.items()}

        def regions(world):
            explored = set(explored_by_world.get(world.lower(), []))
            return sorted(set(ALL_REGION_IDS) | explored) if RAID_REGIONS == "all" else sorted(explored)

        # A world with no explored region has nothing for an explored-only rule
        # to cover, so it is left out rather than given an empty rule.
        worlds = [w for w in requested if regions(w)]
        unexplored = [w for w in requested if not regions(w)]
        limit = _rule_limit("maximumRaidNotificationRules")
        existing = settings.get("raidNotificationRules") or []
        kept = [r for r in existing if r.get("ruleName") != RULE_NAME]
        room = max(0, limit - len(kept))
        applied, skipped = worlds[:room], worlds[room:]

        # A world's rule keeps the id it already has, unless that is the blank one.
        ids = {(r.get("world") or r.get("World") or "").lower(): r.get("ruleId")
               for r in existing if r.get("ruleName") == RULE_NAME}

        def rule_id(world):
            known = ids.get(world.lower())
            return known if known and known != NIL_RULE_ID else str(uuid.uuid4())

        managed = [{
            "ruleId": rule_id(w), "ruleName": RULE_NAME, "World": w, "RegionIds": regions(w),
            "isEnabled": True, "areaRevealed": "appNotifications",
            "subareaRevealed": "appNotifications", "raidStarted": "appNotifications",
        } for w in applied]
        # Stored only when they differ from the bot's rules already on the account:
        # the bot sets them daily and whenever explored areas change, and most of
        # those times nothing has.
        before = [r for r in existing if r.get("ruleName") == RULE_NAME]
        r = None if _same_raid_rules(before, managed) else \
            _observer("POST", "/Settings/StoreUserSettings", bearer=credential, body={**settings, "raidNotificationRules": kept + managed})
        # What each rule covers, for the bot's /observer coverage: the region ids per
        # world that got a rule, every explored area (so the bot can tell when that
        # changes), and whatever name the explored areas carry for an id.
        # `areaFields` lists the fields an explored area has, so a missing name can
        # be traced to the shape rather than guessed at.
        extra = {"regions": {w: regions(w) for w in applied}, "explored": explored_ids,
                 "areaNames": names, "areaFields": fields}
        return _stored(r, applied, skipped, limit, unexplored, extra)
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 502


@app.post("/explored-areas")
def explored_areas():
    """The areas an account has explored, per world, and their names: one read,
    which the bot makes on every raid check to see whether a member has explored
    something new since their raid rules were set. Nothing is stored."""
    b = _json_body()
    credential = b.get("credential")
    if not credential:
        return jsonify({"ok": False, "error": "credential required"}), 400
    try:
        r = _observer("GET", "/Area/ExploredAreas", bearer=credential)
        if r.status_code == 401:
            return jsonify({"ok": False, "status": "unauthorised"}), 401
        if r.status_code != 200:
            return jsonify({"ok": False, "status_code": r.status_code}), 502
        explored, names, _ = _explored_by_world(r.json())
        return jsonify({"ok": True, "status_code": 200, "explored": explored, "areaNames": names})
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 502


@app.post("/raids")
def raids():
    """Currently-announced/active raids for the credential's enabled raid rules."""
    b = _json_body()
    bearer = b.get("bearerToken")
    if not bearer:
        return jsonify({"ok": False, "error": "bearerToken required"}), 400
    try:
        r = _observer("GET", "/Raids", bearer=bearer)
        if r.status_code == 401:
            return jsonify({"ok": False, "status": "unauthorised"}), 401
        return jsonify({"ok": r.status_code == 200, "status_code": r.status_code,
                        "raids": r.json() if r.status_code == 200 else None})
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 502


@app.post("/clear-rules")
def clear_rules():
    """Remove the bot's rules (MWC and raids) on unlink. Other rules untouched."""
    b = _json_body()
    credential = b.get("credential")
    device = b.get("deviceIdentification") or "Violent Bot"
    if not credential:
        return jsonify({"ok": False, "error": "credential required"}), 400
    try:
        settings = _current_settings(credential, device)
        if settings is None:
            return jsonify({"ok": False, "error": "could not read settings"}), 502
        for key in ("miniWorldChangeNotificationRules", "raidNotificationRules"):
            settings[key] = [r for r in (settings.get(key) or []) if r.get("ruleName") != "Violent Bot"]
        r = _observer("POST", "/Settings/StoreUserSettings", bearer=credential, body=settings)
        return jsonify({"ok": r.status_code == 200, "status_code": r.status_code})
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 502


@app.post("/mwc")
def mwc():
    """Currently-active MWC for the credential's enabled rules.

    `GET /MiniWorldChanges` (base) is the live feed — `[{notificationId, world,
    title, body, ruleIds}]` — not `/GetMiniWorldChanges`, which is the static catalog.
    """
    b = _json_body()
    bearer = b.get("bearerToken")
    if not bearer:
        return jsonify({"ok": False, "error": "bearerToken required"}), 400
    try:
        r = _observer("GET", "/MiniWorldChanges", bearer=bearer)
        if r.status_code == 401:
            return jsonify({"ok": False, "status": "unauthorised"}), 401
        return jsonify({"ok": r.status_code == 200, "status_code": r.status_code,
                        "miniWorldChanges": r.json() if r.status_code == 200 else None})
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 502


if __name__ == "__main__":
    app.run(host=HOST, port=PORT)
