#!/bin/sh
set -eu
node=$(garage node id -q | cut -d@ -f1)
if garage layout show | grep -q 'Current cluster layout version: 0'; then
  garage layout assign -z local -c 1G "$node" >/dev/null
  garage layout apply --version 1 >/dev/null
fi
garage bucket info "$S3_BUCKET" >/dev/null 2>&1 || garage bucket create "$S3_BUCKET" >/dev/null
if ! garage key info "$S3_ACCESS_KEY" >/dev/null 2>&1; then
  garage key import --yes -n profile-app "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >/dev/null 2>&1 || { echo 'Object storage key initialization failed' >&2; exit 1; }
fi
garage bucket allow --read --write --key "$S3_ACCESS_KEY" "$S3_BUCKET" >/dev/null
