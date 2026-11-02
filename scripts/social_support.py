#!/usr/bin/env python3
"""Shared local account, HTTP and image fixtures. Never print credentials or tokens."""
import concurrent.futures
import json
import os
import re
import secrets
import struct
import zlib
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
from auth_support import BASE, user_grant, machine_token

MAIL = os.getenv("MAILPIT_URL", "http://127.0.0.1:" + os.getenv("MAILPIT_PORT", "8025"))


def request(method, path, body=None, token=None, headers=None, expected=200):
    headers = dict(headers or {})
    if token:
        headers["Authorization"] = "Bearer " + token
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = Request(BASE + path, data=body, headers=headers, method=method)
    for attempt in range(6):
        try:
            response = urlopen(req, timeout=30)
        except HTTPError as error:
            response = error
        if response.status != 429 or not path.startswith("/auth/v1/") or attempt == 5:
            break
        # Setup accounts share the gateway IP with the other local integration checks.
        delay = min(30, max(1, int(response.headers.get("Retry-After", "12"))))
        response.close()
        time.sleep(delay)
    with response:
        assert (
            response.status == expected
        ), f"{method} {path}: expected {expected}, got {response.status}"
        data = response.read()
        if data and "json" in response.headers.get("Content-Type", ""):
            data = json.loads(data)
        return data, response.headers


def mail_code(address, subject):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        with urlopen(MAIL + "/api/v1/messages", timeout=5) as response:
            messages = json.load(response).get("messages", [])
        for message in messages:
            if subject not in message.get("Subject", ""):
                continue
            if not any(
                person.get("Address") == address for person in message.get("To", [])
            ):
                continue
            with urlopen(
                MAIL + "/api/v1/message/" + message["ID"], timeout=5
            ) as response:
                text = json.load(response).get("Text", "")
            match = re.search(r"(?m)^([A-Za-z0-9_-]{43})\r?$", text)
            if match:
                return match.group(1)
        time.sleep(1)
    raise AssertionError("Expected verification/reset email was not delivered")


def png():
    def chunk(kind, data):
        return (
            struct.pack("!I", len(data))
            + kind
            + data
            + struct.pack("!I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack("!IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
        + chunk(b"tEXt", b"Comment\x00PRIVATE_METADATA_MARKER")
        + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\x00\xff\x00" * 2))
        + chunk(b"IEND", b"")
    )


def account():
    address = "e2e-" + uuid.uuid4().hex + "@example.test"
    password = "Test-" + secrets.token_urlsafe(24)
    request(
        "POST",
        "/auth/v1/registrations",
        {"email": address, "password": password},
        expected=202,
    )
    code = mail_code(address, "Verify")
    request("POST", "/auth/v1/email-verifications", {"token": code}, expected=204)
    request("POST", "/auth/v1/email-verifications", {"token": code}, expected=400)
    tokens, _ = user_grant("openid api.read api.write", address, password)
    return address, password, tokens["access_token"]
