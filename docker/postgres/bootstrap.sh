#!/bin/sh
set -eu
if [ -f /run/secrets/PGPASSWORD ]; then PGPASSWORD=$(cat /run/secrets/PGPASSWORD); export PGPASSWORD; fi
if [ -f /run/secrets/AUTH_DB_PASSWORD ]; then AUTH_DB_PASSWORD=$(cat /run/secrets/AUTH_DB_PASSWORD); export AUTH_DB_PASSWORD; fi
if [ -f /run/secrets/DOMAIN_DB_PASSWORD ]; then DOMAIN_DB_PASSWORD=$(cat /run/secrets/DOMAIN_DB_PASSWORD); export DOMAIN_DB_PASSWORD; fi
: "${AUTH_DB_PASSWORD:?AUTH_DB_PASSWORD required}" "${DOMAIN_DB_PASSWORD:?DOMAIN_DB_PASSWORD required}"
psql -h postgres -U plot_twist -d postgres -v ON_ERROR_STOP=1 -f /bootstrap/roles.sql
