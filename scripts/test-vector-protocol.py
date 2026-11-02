#!/usr/bin/env python3
"""Private Qdrant compatibility check, in a disposable collection. Does not test embeddings."""
import json
import os
import uuid
from urllib.request import Request, urlopen

base = os.getenv("QDRANT_URL", "http://qdrant:6333")
key = os.getenv("QDRANT_API_KEY", "plot-twist-vector-local-key")
collection = "protocol_test_" + uuid.uuid4().hex


def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = Request(
        base + path,
        data=data,
        method=method,
        headers={"api-key": key, "Content-Type": "application/json"},
    )
    with urlopen(request, timeout=10) as response:
        return json.load(response)


points = [str(uuid.uuid4()) for _ in range(3)]
stories = [str(uuid.uuid4()) for _ in range(3)]
author = str(uuid.uuid4())
created = False
try:
    call(
        "PUT",
        "/collections/" + collection,
        {"vectors": {"size": 384, "distance": "Cosine", "on_disk": True}},
    )
    created = True
    call(
        "PUT",
        "/collections/" + collection + "/points?wait=true",
        {
            "points": [
                {
                    "id": points[0],
                    "vector": [1.0] + [0.0] * 383,
                    "payload": {
                        "story_id": stories[0],
                        "author_id": author,
                        "version": 0,
                    },
                },
                {
                    "id": points[1],
                    "vector": [0.99, 0.1] + [0.0] * 382,
                    "payload": {
                        "story_id": stories[1],
                        "author_id": author,
                        "version": 0,
                    },
                },
                {
                    "id": points[2],
                    "vector": [0.0, 1.0] + [0.0] * 382,
                    "payload": {
                        "story_id": stories[2],
                        "author_id": author,
                        "version": 1,
                    },
                },
            ]
        },
    )
    result = call(
        "POST",
        "/collections/" + collection + "/points/query",
        {
            "query": {
                "recommend": {
                    "positive": [points[0]],
                    "negative": [],
                    "strategy": "average_vector",
                }
            },
            "filter": {"must_not": [{"has_id": [points[0]]}]},
            "limit": 2,
            "with_payload": True,
            "with_vector": False,
        },
    )
    assert result["result"]["points"][0]["id"] == points[1]
    scroll = call(
        "POST",
        "/collections/" + collection + "/points/scroll",
        {"limit": 2, "with_payload": True, "with_vector": False},
    )
    assert len(scroll["result"]["points"]) == 2 and scroll["result"]["next_page_offset"]
    call(
        "POST",
        "/collections/" + collection + "/points/delete?wait=true",
        {"filter": {"must": [{"key": "story_id", "match": {"value": stories[1]}}]}},
    )
    result = call(
        "POST", "/collections/" + collection + "/points/scroll", {"limit": 10}
    )
    assert len(result["result"]["points"]) == 2
    print(
        "PASS: real Qdrant cosine recommendation, payload filters, scroll cursors and deletion; no model inference claimed"
    )
finally:
    if created:
        call("DELETE", "/collections/" + collection)
