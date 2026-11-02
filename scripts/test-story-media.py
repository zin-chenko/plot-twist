#!/usr/bin/env python3
"""Local full-stack smoke: OAuth -> gateway -> S3 -> real queued text moderation.

Requires the updated backend, Mailpit and MODERATION_MEDIA_REVIEW_REQUIRED=false.
Never print credentials, signed URLs or image bytes.
"""
from social_support import account, png, request, wait_published


def check():
    _, _, author = account()
    _, _, reader = account()
    media_path = "/api/v1/story-media"
    stories_path = "/api/v1/stories"
    asset = story_id = None
    try:
        request("POST", media_path, png(), headers={"Content-Type": "application/octet-stream"}, expected=401)
        request("POST", media_path, b"<svg></svg>", author, {"Content-Type": "application/octet-stream"}, expected=415)
        asset, _ = request("POST", media_path, png(), author, {"Content-Type": "application/octet-stream"}, expected=201)
        assert asset["kind"] == "IMAGE" and asset["contentType"] == "image/png"
        assert "objectKey" not in asset and "ownerId" not in asset
        asset_route = media_path + "/" + asset["id"]
        request("GET", asset_route + "/access", token=reader, expected=404)
        body = "Утро в парке.\n![Цвета прогулки](attachment:" + asset["id"] + ")\nХороший день."
        request("POST", stories_path, {"title": "Чужой файл", "body": body}, reader, expected=400)
        story, _ = request("POST", stories_path, {"title": "Прогулка с фотографией", "body": body}, author, expected=202)
        story_id = story["id"]
        wait_published(story_id, author)
        story_route = stories_path + "/" + story_id
        published, _ = request("GET", story_route, token=reader)
        assert [a["id"] for a in published["attachments"]] == [asset["id"]]
        access, _ = request("GET", asset_route + "/access", token=reader)
        link = access["url"]
        assert link.startswith(asset_route + "/content?ticket=")
        request("GET", asset_route + "/content", expected=404)
        whole, headers = request("GET", link)
        assert headers["Content-Type"] == "image/png" and whole.startswith(b"\x89PNG")
        assert b"PRIVATE_METADATA_MARKER" not in whole
        partial, headers = request("GET", link, headers={"Range": "bytes=0-7"}, expected=206)
        assert partial == whole[:8] and headers["Content-Range"].startswith("bytes 0-7/")
        _, headers = request("HEAD", link)
        assert int(headers["Content-Length"]) == len(whole)
        request("GET", link, headers={"Range": "bytes=999999999-"}, expected=416)
        request("DELETE", asset_route, token=author, expected=409)
        _, version = request("GET", story_route, token=author)
        request("PUT", story_route, {"title": "Прогулка с фотографией", "body": "Хороший день без вложений."}, author, {"If-Match": version["ETag"]}, expected=202)
        wait_published(story_id, author)
        request("GET", link, expected=404)
        assert request("GET", story_route, token=reader)[0]["attachments"] == []
        request("DELETE", asset_route, token=author, expected=204)
        asset = None
        print("Story media passed: private upload, publication, native download/Range and version revocation")
    finally:
        if story_id:
            _, headers = request("GET", stories_path + "/" + story_id, token=author)
            request("DELETE", stories_path + "/" + story_id, token=author, headers={"If-Match": headers["ETag"]}, expected=204)
        if asset:
            request("DELETE", media_path + "/" + asset["id"], token=author, expected=204)


if __name__ == "__main__":
    check()
