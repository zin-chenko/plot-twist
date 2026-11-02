#!/usr/bin/env python3
"""Operator retry of dead publication jobs, using their original immutable snapshot."""
import subprocess
import argparse

p = argparse.ArgumentParser()
p.add_argument("--all-pending", action="store_true")
args = p.parse_args()
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
        "UPDATE moderation_outbox SET dead=false,attempts=0,sent_at=NULL,available_at=now(),lease_until=NULL,lease_token=NULL WHERE "
        + ("true" if args.all_pending else "dead"),
    ],
    check=True,
)
