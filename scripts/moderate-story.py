#!/usr/bin/env python3
"""Operator-only review resolution using local database access; no public moderation endpoint."""
import argparse
import subprocess
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("story_id", type=uuid.UUID)
parser.add_argument("--revision", type=int, required=True)
choice = parser.add_mutually_exclusive_group(required=True)
choice.add_argument("--approve", action="store_true")
choice.add_argument("--reject", action="store_true")
parser.add_argument("--operator", required=True)
parser.add_argument("--reason", required=True)
args = parser.parse_args()
if (
    args.revision < 0
    or not 1 <= len(args.operator) <= 100
    or not 1 <= len(args.reason) <= 300
):
    parser.error("Invalid revision, operator or reason length")
statement = """
WITH target AS (
 SELECT * FROM stories WHERE id=:'story_id'::uuid AND version=:'revision'::bigint
 AND moderation_status IN ('REVIEW_REQUIRED','REJECTED') FOR UPDATE
), audit AS (
 INSERT INTO moderation_decisions(job_id,story_id,revision,content_hash,policy_version,rules_hash,outcome,rule_ids,source,operator_name,operator_reason)
 SELECT moderation_job_id,id,version,moderation_hash,'basic-v1',repeat('0',64),:'outcome','[]'::jsonb,'operator',:'operator',:'reason' FROM target RETURNING story_id
)
UPDATE stories s SET moderation_status=CASE WHEN :'outcome'='APPROVE' THEN 'PUBLISHED' ELSE 'REJECTED' END,
 moderation_reason=CASE WHEN :'outcome'='APPROVE' THEN NULL ELSE :'reason' END,
 published_title=CASE WHEN :'outcome'='APPROVE' THEN s.title ELSE s.published_title END,
 published_body=CASE WHEN :'outcome'='APPROVE' THEN s.body ELSE s.published_body END,
 published_version=CASE WHEN :'outcome'='APPROVE' THEN s.version ELSE s.published_version END,
 published_updated_at=CASE WHEN :'outcome'='APPROVE' THEN now() ELSE s.published_updated_at END
FROM audit WHERE s.id=audit.story_id RETURNING s.id;
"""
command = [
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
    "-v",
    "outcome=" + ("APPROVE" if args.approve else "BLOCK"),
    "-v",
    "operator=" + args.operator,
    "-v",
    "reason=" + args.reason,
]
result = subprocess.run(
    command, input=statement, text=True, capture_output=True, check=True
)
if str(args.story_id) not in result.stdout.splitlines():
    raise SystemExit(
        "No change: story is missing, revision changed or there is no reviewable decision"
    )
print("Moderation decision applied and audited:", args.story_id)
