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
