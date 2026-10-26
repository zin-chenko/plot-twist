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
from auth_support import BASE, machine_token, token_request, bearer_request


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class CsrfParser(HTMLParser):
    value = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "input" and attrs.get("name") == "_csrf":
            self.value = attrs["value"]


def redirect(opener, request):
    try:
        with opener.open(request, timeout=15):
            raise AssertionError("Expected OAuth redirect")
    except HTTPError as error:
        assert error.code == 302, f"Expected redirect, received HTTP {error.code}"
        return error.headers["Location"]


def expect_status(request, status, opener=None):
    try:
        with (opener.open(request, timeout=15) if opener else urlopen(request, timeout=15)) as response:
            assert response.status == status, response.status
    except HTTPError as error:
        assert error.code == status, (error.code, status)


def user_grant(scope="openid api.read"):
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    state = secrets.token_urlsafe(24)
    callback = os.getenv("AUTH_REDIRECT_URI", "http://127.0.0.1:18080/callback")
    fields = {"response_type": "code", "client_id": "plot-twist-web", "redirect_uri": callback,
              "scope": scope, "state": state, "code_challenge": challenge, "code_challenge_method": "S256"}
    authorize = Request(BASE + "/oauth2/authorize?" + urlencode(fields), headers={"Accept": "text/html"})
    opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect())
    login = redirect(opener, authorize)
    assert urlparse(login).path == "/login", "Expected login form"
    assert urlparse(login).netloc == urlparse(BASE).netloc, "Login escaped public gateway origin"
    with opener.open(BASE + "/login", timeout=15) as response:
        parser = CsrfParser()
        parser.feed(response.read().decode())
    assert parser.value, "Login form has no CSRF token"
    credentials = {"username": os.getenv("AUTH_BOOTSTRAP_USERNAME", "demo"),
                   "password": os.getenv("AUTH_BOOTSTRAP_PASSWORD", "local-demo-password"),
                   "_csrf": parser.value}
    # Login must reject a missing CSRF token.
    expect_status(Request(BASE + "/login", data=urlencode({k: v for k, v in credentials.items() if k != "_csrf"}).encode()), 403, opener)
    location = redirect(opener, Request(BASE + "/login", data=urlencode(credentials).encode()))
    assert "error" not in parse_qs(urlparse(location).query), "User login failed"
    location = redirect(opener, authorize)
    result = parse_qs(urlparse(location).query)
    assert result.get("state") == [state], "OAuth state mismatch"
    assert "code" in result, "Authorization code was not issued"
    exchange = {"grant_type": "authorization_code", "client_id": "plot-twist-web",
                "redirect_uri": callback, "code": result["code"][0], "code_verifier": verifier}
    tokens = token_request(exchange, basic=False)
    if "id_token" in tokens:
        expect_status(bearer_request("/api/story", tokens["id_token"]), 401)
    return tokens, exchange


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
    if "--restart" in sys.argv:
        check_restart()
