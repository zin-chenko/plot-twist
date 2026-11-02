#!/usr/bin/env python3
"""Local operator command; changes only derived indexing jobs, never story content."""
import argparse
import subprocess

p = argparse.ArgumentParser()
group = p.add_mutually_exclusive_group(required=True)
group.add_argument("--all", action="store_true")
group.add_argument("--retry-dead", action="store_true")
args = p.parse_args()
statement = (
    """INSERT INTO story_vector_jobs(story_id) SELECT id FROM stories WHERE true ON CONFLICT(story_id) DO UPDATE SET generation=story_vector_jobs.generation+1,attempts=0,dead=false,available_at=now(),last_error=NULL"""
    if args.all
    else "UPDATE story_vector_jobs SET attempts=0,dead=false,available_at=now(),last_error=NULL WHERE dead"
)
subprocess.run(
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
        "-v",
        "ON_ERROR_STOP=1",
        "-c",
        statement,
    ],
    check=True,
)
