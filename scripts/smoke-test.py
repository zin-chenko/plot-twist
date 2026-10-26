#!/usr/bin/env python3
"""Validate the public gateway, service chain and balancing (Python 3)."""
import json
import os
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASE = os.getenv("GATEWAY_URL", "http://127.0.0.1:" + os.getenv("GATEWAY_PORT", "8080"))
from auth_support import machine_token

EXPECTED = int(os.getenv("EXPECTED_API_INSTANCES", "2"))


def request(path, headers=None):
    with urlopen(Request(BASE + path, headers=headers or {}), timeout=15) as response:
        return response.read(), response.headers


def check():
    token = machine_token()
    authenticated = {"Authorization": "Bearer " + token}
    body, _ = request("/readyz")
    assert json.loads(body)["status"] == "UP", body
    instances = set()
    for _ in range(24):
        body, headers = request("/api/story", {**authenticated, "X-Request-ID": "untrusted-client-value"})
        assert json.loads(body) == {"service": "api-service", "status": "running"}, body
        assert headers["X-Request-ID"] and headers["X-Request-ID"] != "untrusted-client-value"
        assert headers["X-Backend-Instance"], "Missing backend identity"
        instances.add(headers["X-Backend-Instance"])
        time.sleep(0.03)
    assert len(instances) >= EXPECTED, f"Expected {EXPECTED} backends, got {instances}"
    body, _ = request("/api/character", authenticated)
    assert json.loads(body) == {"service": "domain-service", "status": "running"}, body
    try:
        request("/api/story")
    except HTTPError as error:
        assert error.code == 401, error.code
    else:
        raise AssertionError("API accepted an anonymous request")
    for path in ("/actuator/health", "/unknown"):
        try:
            request(path)
        except HTTPError as error:
            assert error.code == 404, (path, error.code)
        else:
            raise AssertionError(f"Unexpected public route: {path}")
    print(f"Gateway OK; {len(instances)} API replicas; JWT/API -> domain OK; anonymous access denied; actuator blocked")


if __name__ == "__main__":
    deadline = time.monotonic() + 90
    while True:
        try:
            check()
            break
        except (URLError, OSError, AssertionError, ValueError) as error:
            if time.monotonic() >= deadline:
                raise SystemExit(f"Gateway smoke test failed: {error}") from error
            time.sleep(2)
