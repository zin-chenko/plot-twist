#!/usr/bin/env python3
"""Validate real OAuth2 user/machine grants and enforcement through the gateway."""
import base64
import hashlib
import http.cookiejar
import json
import os
import secrets
import subprocess
import sys
from html.parser import HTMLParser
from urllib.error import HTTPError
from urllib.parse import urlencode, urlparse, parse_qs
from urllib.request import Request, build_opener, HTTPCookieProcessor, HTTPRedirectHandler, urlopen
from auth_support import BASE, machine_token, token_request, bearer_request, user_grant, expect_status


def check():
    for path in ("/api/story", "/api/character", "/api/me"):
        expect_status(Request(BASE + path), 401)
        expect_status(bearer_request(path, "not-a-jwt"), 401)
    token = machine_token()
    for path in ("/api/story", "/api/character"):
        expect_status(bearer_request(path, token), 200)
    parts = token.split(".")
    payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
    payload["sub"] = "forged-user"
    parts[1] = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
    expect_status(bearer_request("/api/story", ".".join(parts)), 401)
    user_tokens, exchange = user_grant()
    user_token = user_tokens["access_token"]
    with urlopen(bearer_request("/api/me", user_token), timeout=15) as response:
        assert json.load(response)["subject"] == os.getenv("AUTH_BOOTSTRAP_USERNAME", "demo")
    expect_status(bearer_request("/api/character", user_token), 200)
    with urlopen(bearer_request("/userinfo", user_token), timeout=15) as response:
        assert json.load(response)["sub"] == os.getenv("AUTH_BOOTSTRAP_USERNAME", "demo")
    without_tokens, _ = user_grant("openid")
    without_scope = without_tokens["access_token"]
    expect_status(bearer_request("/api/story", without_scope), 403)
    expect_status(bearer_request("/api/character", without_scope), 403)
    try:
        token_request(exchange, basic=False)
        raise AssertionError("Authorization code reused")
    except HTTPError as error:
        assert error.code == 400, error.code
    # The issuer invalidates the authorization on replay; stateless resource servers
    # still accept the already-issued JWT until expiry (documented revocation boundary).
    expect_status(bearer_request("/userinfo", user_token), 401)
    with urlopen(BASE + "/oauth2/jwks", timeout=15) as response:
        for key in json.load(response)["keys"]:
            assert "d" not in key and "p" not in key and "q" not in key, "JWKS exposed private key"
    print("PASS: machine grant; user login + PKCE; CSRF; single-use code; token relay; 401/403; signature; public-only JWKS")


def check_code_race():
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier
    exchange = user_grant(prepare_only=True)
    barrier = Barrier(4)
    def redeem(_):
        barrier.wait()
        try:
            token_request(exchange, basic=False)
            return 200
        except HTTPError as error:
            return error.code
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(redeem, range(4)))
    assert results.count(200) == 1 and all(code in (200, 400, 401) for code in results), results
    print("PASS: concurrent code redemption permits exactly one successful exchange")


def check_restart():
    token = machine_token()
    with urlopen(BASE + "/oauth2/jwks", timeout=15) as response:
        before = {key["kid"] for key in json.load(response)["keys"]}
    expect_status(bearer_request("/api/story", token), 200)
    expect_status(bearer_request("/api/character", token), 200)
    try:
        subprocess.run(["docker", "compose", "stop", "auth-service"], check=True)
        expect_status(bearer_request("/api/story", token), 200)
        expect_status(bearer_request("/api/character", token), 200)
    finally:
        subprocess.run(["docker", "compose", "up", "-d", "--no-build", "--wait"], check=True)
    with urlopen(BASE + "/oauth2/jwks", timeout=15) as response:
        after = {key["kid"] for key in json.load(response)["keys"]}
    assert before == after, "Signing identity changed after auth restart"
    expect_status(bearer_request("/api/story", token), 200)
    expect_status(bearer_request("/api/character", machine_token()), 200)
    print("PASS: warm JWT validation during auth outage; signing identity and issued tokens survive restart")


if __name__ == "__main__":
    check()
    check_code_race()
    if "--restart" in sys.argv:
        check_restart()
