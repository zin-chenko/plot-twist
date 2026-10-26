"""OAuth helpers for local integration checks. Never print credentials or tokens."""
import base64
import json
import os
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BASE = os.getenv("GATEWAY_URL", "http://127.0.0.1:" + os.getenv("GATEWAY_PORT", "8080"))


def token_request(fields, basic=True, opener=None):
    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    if basic:
        secret = os.getenv("AUTH_CLIENT_SECRET", "local-client-secret")
        credentials = base64.b64encode(("plot-twist-cli:" + secret).encode()).decode()
        headers["Authorization"] = "Basic " + credentials
    request = Request(BASE + "/oauth2/token", data=urlencode(fields).encode(), headers=headers)
    with (opener.open(request, timeout=15) if opener else urlopen(request, timeout=15)) as response:
        return json.load(response)


def machine_token():
    return token_request({"grant_type": "client_credentials", "scope": "api.read"})["access_token"]


def bearer_request(path, token, headers=None):
    return Request(BASE + path, headers={**(headers or {}), "Authorization": "Bearer " + token})

# Protocol helpers used only by integration checks (the public CLI uses a browser).
import hashlib
import http.cookiejar
import secrets
from html.parser import HTMLParser
from urllib.error import HTTPError
from urllib.parse import urlparse, parse_qs
from urllib.request import build_opener, HTTPCookieProcessor, HTTPRedirectHandler

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


def user_grant(scope="openid api.read", username=None, password=None, prepare_only=False):
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
    credentials = {"username": (username or os.getenv("AUTH_BOOTSTRAP_USERNAME", "demo")),
                   "password": (password or os.getenv("AUTH_BOOTSTRAP_PASSWORD", "local-demo-password")),
                   "_csrf": parser.value}
    # Login must reject a missing CSRF token.
    expect_status(Request(BASE + "/login", data=urlencode({k: v for k, v in credentials.items() if k != "_csrf"}).encode()), 403, opener)
    location = redirect(opener, Request(BASE + "/login", data=urlencode(credentials).encode()))
    assert "error" not in parse_qs(urlparse(location).query, keep_blank_values=True), "User login failed"
    location = redirect(opener, authorize)
    result = parse_qs(urlparse(location).query, keep_blank_values=True)
    assert result.get("state") == [state], "OAuth state mismatch"
    assert "code" in result, "Authorization code was not issued"
    exchange = {"grant_type": "authorization_code", "client_id": "plot-twist-web",
                "redirect_uri": callback, "code": result["code"][0], "code_verifier": verifier}
    if prepare_only:
        return exchange
    tokens = token_request(exchange, basic=False)
    if "id_token" in tokens:
        expect_status(bearer_request("/api/story", tokens["id_token"]), 401)
    return tokens, exchange
