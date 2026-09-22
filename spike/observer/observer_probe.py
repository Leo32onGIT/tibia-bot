#!/usr/bin/env python3
"""Phase 0 spike — validate the Tibia Observer API path from a server context.

Authorised by CipSoft (as-is, unsupported). Proves: Cloudflare passable via
browser-TLS impersonation, then Status -> LoginWithAccessToken -> GetMiniWorldChanges.
Structured so /Raids slots in later with the same bearer.

Usage:
  python observer_probe.py                 # token-less rungs (reachability + contracts)
  python observer_probe.py --token ABCDE   # full flow (LINKS the account; one-device!)
"""
import argparse, json, sys, uuid
from curl_cffi import requests

BASE = "https://observer.tibia.com/api/v1"
IMPERSONATE = "chrome"          # browser TLS fingerprint -> passes Cloudflare managed challenge
DEVICE_ID = "Violent Bot"       # the DeviceIdentification label shown on the account link


def _req(method, path, token=None, body=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = "application/json"
    r = requests.request(method, f"{BASE}{path}", headers=headers,
                         data=json.dumps(body) if body is not None else None,
                         impersonate=IMPERSONATE, timeout=20)
    return r


def status():
    r = _req("GET", "/Status")
    print(f"[Status]  HTTP {r.status_code}  cf-mitigated={r.headers.get('cf-mitigated')}")
    ver = None
    if r.status_code == 200:
        d = r.json()
        ver = d.get("minimalClientVersion")
        print(f"          isAvailable={d.get('isAvailable')}  minimalClientVersion={ver}")
    return ver


def login(access_token, client_version):
    body = {
        "AccessToken": access_token,
        "ClientVersion": client_version,
        "DeviceIdentification": DEVICE_ID,
        "AdditionalAccessTokens": [],
    }
    r = _req("POST", "/Account/LoginWithAccessToken", body=body)
    print(f"[Login]   HTTP {r.status_code}")
    print("          " + r.text[:500])
    if r.status_code == 200:
        return r.json()          # expected: bearer + refresh + expiry (shape TBD on first real run)
    return None


def login_contract_probe():
    """No token: confirm the endpoint is reachable through Cloudflare and echo its contract."""
    r = _req("POST", "/Account/LoginWithAccessToken", body={})
    print(f"[Login?]  HTTP {r.status_code} (empty body -> validation contract)")
    print("          " + r.text[:400])


def mini_world_changes(bearer):
    r = _req("GET", "/MiniWorldChanges/GetMiniWorldChanges", token=bearer)
    print(f"[MWC]     HTTP {r.status_code}")
    print("          " + r.text[:600])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--token", help="5-char access token from tibia.com (LINKS the account)")
    args = ap.parse_args()

    ver = status() or "1.1.5"
    if not args.token:
        login_contract_probe()
        mini_world_changes(bearer=None)     # expect 401
        print("\nToken-less rungs done. Re-run with --token <5-char> for the full flow.")
        return

    session = login(args.token, ver)
    if not session:
        print("Login failed — see body above.")
        sys.exit(1)
    bearer = session.get("accessToken") or session.get("AccessToken")
    if not bearer:
        print("No bearer in login response; inspect the shape above.")
        sys.exit(1)
    mini_world_changes(bearer)


if __name__ == "__main__":
    main()
