#!/usr/bin/env python3
"""Trusted operator preview. Saves private HTML with five-minute media links; prints no tickets."""
import argparse
import base64
import hashlib
import hmac
import html
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.parse import urlsplit
import uuid

LOCAL_KEY = "plot-twist-local-story-media-key-change-before-production"


def ticket(asset, owner, key):
    message = f"1|{uuid.UUID(asset)}|{uuid.UUID(owner)}|{int(time.time())+300}".encode()
    encoded = base64.urlsafe_b64encode(message).rstrip(b"=")
    signature = base64.urlsafe_b64encode(
        hmac.new(key, encoded, hashlib.sha256).digest()
    ).rstrip(b"=")
    return (encoded + b"." + signature).decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("story_id", type=uuid.UUID)
    parser.add_argument("--revision", type=int, required=True)
    parser.add_argument("--origin", default="http://localhost:8080")
    parser.add_argument("--key-file", type=Path)
    args = parser.parse_args()
    origin = urlsplit(args.origin)
    if (
        origin.scheme not in ("http", "https")
        or not origin.hostname
        or origin.username
        or origin.password
        or origin.path not in ("", "/")
        or origin.query
        or origin.fragment
        or args.revision < 0
    ):
        parser.error("Use a valid API origin and nonnegative revision")
    if not args.key_file and origin.hostname not in ("localhost", "127.0.0.1"):
        parser.error(
            "A production API requires --key-file pointing to its story-media-signing-key"
        )
    key = args.key_file.read_bytes() if args.key_file else LOCAL_KEY.encode()
    if len(key) < 32:
        parser.error("Signing key must have at least 32 bytes")
    sql = """
    SELECT jsonb_build_object('title',s.title,'body',s.body,'attachments',COALESCE((
      SELECT jsonb_agg(jsonb_build_object('id',a.id,'owner',a.owner_id,'kind',a.kind) ORDER BY r.position)
      FROM story_attachment_refs r JOIN story_attachments a ON a.id=r.attachment_id AND a.state='READY'
      WHERE r.story_id=s.id AND r.revision=s.version),'[]'::jsonb))
    FROM stories s WHERE id=:'story_id'::uuid AND version=:'revision'::bigint;
    """
    result = subprocess.run(
        [
            "docker",
            "compose",
            "exec",
            "-T",
            "postgres",
            "psql",
            "-XAt",
            "-U",
            "app_domain",
            "-d",
            "profiles",
            "-v",
            "ON_ERROR_STOP=1",
            "-v",
            "story_id=" + str(args.story_id),
            "-v",
            "revision=" + str(args.revision),
        ],
        input=sql,
        text=True,
        capture_output=True,
        check=True,
    )
    if not result.stdout.strip():
        raise SystemExit(
            "Story missing or revision changed; reload the current version"
        )
    data = json.loads(result.stdout)
    base = args.origin.rstrip("/")
    policy = html.escape(
        "default-src 'none'; img-src "
        + base
        + "; media-src "
        + base
        + "; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'",
        quote=True,
    )
    parts = [
        f'<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="{policy}">',
        "<title>Просмотр для модерации</title><style>body{max-width:900px;margin:2rem auto;font:16px system-ui;padding:1rem}pre{white-space:pre-wrap}img,video{max-width:100%;max-height:600px}figure{margin:1rem 0}</style>",
        "<h1>"
        + html.escape(data["title"])
        + "</h1><pre>"
        + html.escape(data["body"])
        + "</pre>",
    ]
    for asset in data["attachments"]:
        asset_id = str(uuid.UUID(asset["id"]))
        url = html.escape(
            base
            + "/api/v1/story-media/"
            + asset_id
            + "/content?ticket="
            + ticket(asset_id, asset["owner"], key),
            quote=True,
        )
        parts.append(
            f'<figure><video controls preload="metadata" src="{url}"></video></figure>'
            if asset["kind"] == "VIDEO"
            else f'<figure><img src="{url}" alt="Вложение для проверки"></figure>'
        )
    parts.append(
        "<p>Ссылки действуют пять минут. Для нового просмотра запустите команду снова.</p></html>"
    )
    directory = Path(".credentials")
    directory.mkdir(mode=0o700, exist_ok=True)
    target = directory / f"story-review-{args.story_id}-{args.revision}.html"
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        output.write("\n".join(parts))
    print("Private operator preview saved:", target.resolve())


if __name__ == "__main__":
    main()
