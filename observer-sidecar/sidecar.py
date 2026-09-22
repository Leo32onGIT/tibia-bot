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
"""
import json
import os

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
    token = (b.get("accessToken") or "").strip()  # case-sensitive: never change case
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
    return jsonify({
        "ok": status == "success",
        "status": status,                      # success | invalidAccessToken | ...
        "bearerToken": d.get("bearerToken"),
        "refresh": d.get("refresh"),           # durable credential -> bot stores this
        "expires": d.get("expires"),
        "accountLabel": (accounts[0].get("accountTitle") if accounts else None),
        "accountCount": len(accounts),
        "raw": d,                              # full shape, so the bot can adapt if a field moves
    })


@app.post("/refresh")
def refresh():
    """Mint a fresh access token from the durable credential.

    Account/login is the app's re-login endpoint; it returns 401 to an
    unauthenticated call, so it wants the credential presented in the header. This
    sends the refresh token as the bearer — TO CONFIRM against a live refresh the
    first time an access token actually expires; adjust here alone if it differs.
    """
    b = _json_body()
    refresh_token = b.get("refresh")
    device = b.get("deviceIdentification") or "Violent Bot"
    client_version = b.get("clientVersion") or CLIENT_VERSION
    if not refresh_token:
        return jsonify({"ok": False, "error": "refresh required"}), 400
    try:
        r = _observer("POST", "/Account/login", bearer=refresh_token, body={
            "ClientVersion": client_version,
            "DeviceIdentification": device,
        })
        d = r.json() if r.text else {}
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 502
    return jsonify({
        "ok": r.status_code == 200 and bool(d.get("bearerToken")),
        "status_code": r.status_code,
        "bearerToken": d.get("bearerToken"),
        "refresh": d.get("refresh", refresh_token),
        "expires": d.get("expires"),
        "raw": d,
    })


@app.post("/mwc")
def mwc():
    """Phase 3 will use this. Returns the MWC payload for a valid bearer."""
    b = _json_body()
    bearer = b.get("bearerToken")
    if not bearer:
        return jsonify({"ok": False, "error": "bearerToken required"}), 400
    try:
        r = _observer("GET", "/MiniWorldChanges/GetMiniWorldChanges", bearer=bearer)
        if r.status_code == 401:
            return jsonify({"ok": False, "status": "unauthorised"}), 401
        return jsonify({"ok": r.status_code == 200, "status_code": r.status_code,
                        "miniWorldChanges": r.json() if r.status_code == 200 else None})
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 502


if __name__ == "__main__":
    app.run(host=HOST, port=PORT)
