#!/usr/bin/env python3
"""Check both HTTP APIs and database-backed readiness (Python 3, no dependencies)."""
import json
import os
import time
from urllib.error import URLError
from urllib.request import urlopen


def read(url):
    with urlopen(url, timeout=3) as response:
        return json.load(response)


for name, port in (("story", os.getenv("STORY_PORT", "8081")),
                   ("character", os.getenv("CHARACTER_PORT", "8082"))):
    base = f"http://127.0.0.1:{port}"
    deadline = time.monotonic() + 90
    while True:
        try:
            health = read(base + "/actuator/health/readiness")
            if health.get("status") != "UP":
                raise ValueError(f"Unexpected readiness: {health}")
            info = read(base + f"/api/{name}")
            if info != {"service": f"{name}-service", "status": "running"}:
                raise ValueError(f"Unexpected API response: {info}")
            print(f"{name}-service: API and PostgreSQL readiness OK")
            break
        except (URLError, OSError, ValueError) as error:
            if time.monotonic() >= deadline:
                raise SystemExit(f"{name}-service failed: {error}") from error
            time.sleep(1)
