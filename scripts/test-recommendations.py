#!/usr/bin/env python3
"""Real local API/SQL checks. --semantic additionally REQUIRES actual E5/Qdrant indexing."""
import json
import sys
import time
import uuid
import subprocess
from urllib.parse import quote
from social_support import request, account
from auth_support import machine_token


def sql(statement):
    result = subprocess.run(
        [
            "docker",
            "compose",
            "exec",
            "-T",
            "postgres",
            "psql",
            "-U",
            "app_domain",
            "-d",
            "profiles",
            "-At",
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            statement,
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def check():
    _, _, author = account()
    _, _, reader = account()
    route = "/api/v1/stories"
    created = []
    fixtures = [
        (
            "Мой первый марафон",
            "Я готовился к марафону несколько месяцев. Длинные пробежки, контроль пульса и тренировки помогли мне преодолеть сорок два километра.",
        ),
        (
            "Как я пробежал 42 километра",
            "История подготовки к первому марафонскому забегу. Я постепенно увеличивал беговую дистанцию, следил за пульсом и тренировался каждую неделю.",
        ),
        (
            "Пирог с яблоками",
            "Смешал муку, яйца и сахар, нарезал яблоки. Пирог выпекался в духовке сорок минут. Получился вкусный домашний десерт.",
        ),
        (
            "Индексы PostgreSQL",
            "Оптимизировал SQL запросы: EXPLAIN ANALYZE показал полное сканирование таблицы. Составной индекс ускорил выборку.",
        ),
        (
            "Путешествие на поезде",
            "Проехал на поезде через несколько городов. Остановки, уютные гостиницы и прогулки по старым улицам сделали отпуск незабываемым.",
        ),
    ]
    try:
        for title, body in fixtures:
            story, _ = request(
                "POST", route, {"title": title, "body": body}, author, expected=201
            )
            created.append(story["id"])
        own, _ = request(
            "POST",
            route,
            {"title": "Own excluded", "body": "My own story"},
            reader,
            expected=201,
        )
        request("GET", "/api/v1/recommendations", expected=401)
        request("GET", "/api/v1/recommendations", token=machine_token(), expected=403)
        request("GET", "/api/v1/recommendations?limit=21", token=reader, expected=400)
        request("GET", "/api/v1/recommendations?cursor=bad", token=reader, expected=400)
        page, h = request("GET", "/api/v1/recommendations?limit=2", token=reader)
        assert h["Cache-Control"] == "no-store" and page["mode"] in [
            "personalized",
            "cold_start",
            "fallback",
        ]
        assert own["id"] not in [x["id"] for x in page["items"]]
        assert page["nextCursor"]
        request(
            "GET",
            "/api/v1/recommendations?cursor=" + quote(page["nextCursor"]),
            token=author,
            expected=400,
        )
        next_page, _ = request(
            "GET",
            "/api/v1/recommendations?limit=2&cursor=" + quote(page["nextCursor"]),
            token=reader,
        )
        assert not (
            {x["id"] for x in page["items"]} & {x["id"] for x in next_page["items"]}
        )
        request("PUT", route + "/" + created[0] + "/reaction", {"value": 1}, reader)
        request("PUT", route + "/" + created[2] + "/reaction", {"value": -1}, reader)
        if "--semantic" in sys.argv:
            deadline = time.monotonic() + 180
            identifiers = ",".join(
                "'" + str(uuid.UUID(value)) + "'" for value in created
            )
            while int(
                sql(
                    "SELECT count(*) FROM story_vector_state WHERE story_id IN ("
                    + identifiers
                    + ")"
                )
            ) < len(created):
                assert (
                    time.monotonic() < deadline
                ), "Real embedding indexing did not finish; check model-download, embeddings and vector jobs"
                time.sleep(2)
            personal, _ = request(
                "GET", "/api/v1/recommendations?refresh=true&limit=10", token=reader
            )
            assert personal["mode"] == "personalized", "Semantic mode was not used"
            ids = [story["id"] for story in personal["items"]]
            assert (
                created[1] in ids[:2]
            ), "Russian marathon paraphrase was not ranked near the top"
            assert (
                created[0] not in ids and created[2] not in ids and own["id"] not in ids
            )
            # Editing requires another version to be indexed; stale vectors may never become cards.
            changed, h = request("GET", route + "/" + created[1], token=author)
            request(
                "PUT",
                route + "/" + created[1],
                {
                    "title": fixtures[1][0],
                    "body": fixtures[1][1] + " Теперь тренируюсь с друзьями.",
                },
                author,
                {"If-Match": h["ETag"]},
            )
            deadline = time.monotonic() + 90
            while (
                sql(
                    "SELECT story_version FROM story_vector_state WHERE story_id='"
                    + created[1]
                    + "'"
                )
                != "1"
            ):
                assert time.monotonic() < deadline, "Edited version was not reindexed"
                time.sleep(2)
        personal, _ = request(
            "GET", "/api/v1/recommendations?refresh=true", token=reader
        )
        assert created[0] not in [x["id"] for x in personal["items"]] and created[
            2
        ] not in [x["id"] for x in personal["items"]]
        read_path = route + "/" + created[3] + "/reading"
        request(
            "POST", read_path, {"completed": True, "seconds": 1}, reader, expected=400
        )
        request(
            "POST", read_path, {"completed": True, "seconds": 25}, reader, expected=204
        )
        revision = sql("SELECT sum(revision) FROM recommendation_users")
        request(
            "POST", read_path, {"completed": True, "seconds": 25}, reader, expected=204
        )
        assert (
            sql("SELECT sum(revision) FROM recommendation_users") == revision
        ), "Duplicate reading invalidated recommendations again"
        request(
            "POST",
            route + "/" + str(uuid.uuid4()) + "/reading",
            {"completed": False, "seconds": 0},
            reader,
            expected=404,
        )
        personal, _ = request(
            "GET", "/api/v1/recommendations?refresh=true", token=reader
        )
        assert created[3] not in [
            x["id"] for x in personal["items"]
        ], "Recently read story was repeated"
        print(
            "PASS: private recommendation snapshots, pagination, ratings/reading exclusions and idempotent reading signals"
        )
        if "--semantic" in sys.argv:
            print(
                "PASS: real ONNX E5 indexing, Russian semantic recommendations and edited-version reindexing"
            )
        else:
            print(
                "Semantic inference was NOT checked; rerun with --semantic after the real model is ready."
            )
    finally:
        for identifier, token in [(value, author) for value in created] + (
            [(own["id"], reader)] if "own" in locals() else []
        ):
            try:
                _, h = request("GET", route + "/" + identifier, token=token)
                request(
                    "DELETE",
                    route + "/" + identifier,
                    token=token,
                    headers={"If-Match": h["ETag"]},
                    expected=204,
                )
            except AssertionError:
                pass


if __name__ == "__main__":
    check()
