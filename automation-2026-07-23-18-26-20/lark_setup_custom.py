#!/usr/bin/env python3
"""Custom lark-cli device-flow setup that calls lark-cli by full Windows path.

Mirrors lark_setup.py but fixes the Windows subprocess resolution issue:
lark-cli is an npm .cmd shim, so we must invoke it via shell=True with the
full path, otherwise CreateProcess raises WinError 2 (file not found).
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

LARK_CLI = r"C:\Users\YTO-02231406\.workbuddy\binaries\node\cli-connector-packages\lark-cli.cmd"

ENDPOINTS = {
    "feishu": {"accounts": "https://accounts.feishu.cn", "open": "https://open.feishu.cn"},
    "lark": {"accounts": "https://accounts.larksuite.com", "open": "https://open.larksuite.com"},
}
CLI_VERSION = "1.0.0"


def post_form(url, data):
    body = urllib.parse.urlencode(data).encode()
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return json.loads(raw)
        except Exception:
            raise RuntimeError(f"HTTP {e.code}: {raw.decode(errors='replace')}")


def begin_registration(brand):
    url = ENDPOINTS["feishu"]["accounts"] + "/oauth/v1/app/registration"
    resp = post_form(url, {
        "action": "begin", "archetype": "PersonalAgent",
        "auth_method": "client_secret", "request_user_info": "open_id tenant_brand",
    })
    if "error" in resp:
        raise RuntimeError(f"Registration failed: {resp.get('error_description', resp['error'])}")
    return resp


def poll_registration(device_code, brand, interval, expires_in):
    url = ENDPOINTS[brand]["accounts"] + "/oauth/v1/app/registration"
    deadline = time.time() + expires_in
    attempts = 0
    while time.time() < deadline and attempts < 200:
        attempts += 1
        time.sleep(interval)
        resp = post_form(url, {"action": "poll", "device_code": device_code})
        err = resp.get("error", "")
        if not err and resp.get("client_id"):
            return resp
        if err == "authorization_pending":
            print("  Waiting...", flush=True)
            continue
        elif err == "slow_down":
            interval = min(interval + 5, 60)
            continue
        elif err == "access_denied":
            raise RuntimeError("Authorization denied by user.")
        elif err in ("expired_token", "invalid_grant"):
            raise RuntimeError("Device code expired. Please try again.")
        elif err:
            raise RuntimeError(f"Poll error: {resp.get('error_description', err)}")
    raise RuntimeError("Authorization timed out. Please try again.")


def save_config(app_id, app_secret, brand):
    proc = subprocess.run(
        [LARK_CLI, "config", "init", "--app-id", app_id,
         "--app-secret-stdin", "--brand", brand],
        input=app_secret, capture_output=True, text=True, shell=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"lark-cli config init failed:\n{proc.stderr}")
    print(f"  {proc.stderr.strip()}" if proc.stderr.strip() else "  Config saved.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--brand", choices=["feishu", "lark"], default="feishu")
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()
    brand = args.brand
    print(f"[lark-setup-custom] Starting device flow (brand={brand})...")
    reg = begin_registration(brand)
    device_code = reg["device_code"]
    expires_in = int(reg.get("expires_in", 300))
    interval = int(reg.get("interval", 5))
    verification_url = reg.get("verification_uri_complete") or reg.get("verification_uri", "")
    print(f"\n  Open this URL in your browser to authorize:\n  {verification_url}\n")
    print("[lark-setup-custom] Waiting for browser authorization...")
    result = poll_registration(device_code, brand, interval, expires_in)
    user_info = result.get("user_info", {}) or {}
    tenant_brand = user_info.get("tenant_brand", brand)
    if not result.get("client_secret") and tenant_brand == "lark":
        result = poll_registration(device_code, "lark", interval, expires_in)
        tenant_brand = "lark"
    app_id = result["client_id"]
    app_secret = result["client_secret"]
    final_brand = tenant_brand if tenant_brand in ("feishu", "lark") else brand
    print(f"[lark-setup-custom] Authorized! App ID: {app_id}")
    print("[lark-setup-custom] Saving config (full lark-cli path)...")
    save_config(app_id, app_secret, final_brand)
    print(f"\n[lark-setup-custom] Done! Run `lark-cli config view` to verify.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[lark-setup-custom] Cancelled.")
        sys.exit(1)
    except Exception as e:
        print(f"\n[lark-setup-custom] Error: {e}", file=sys.stderr)
        sys.exit(1)
