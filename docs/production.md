# Production deployment baseline

The repository contains an application-ready backend and a hardened **single-Linux-host** deployment configuration. A real production deployment is not performed by building these files. DNS, provider accounts, secrets, backups, monitoring and target capacity remain environment-specific decisions.

## Prerequisites

- Linux host with Docker Engine and Compose ≥ 2.24.4. Use a separate production project and reviewed Git commit; do not reuse a development project/volume unintentionally.
- Public DNS for `APP_DOMAIN`, inbound TCP 80/443 (UDP 443 optional for HTTP/3), outbound ACME access. Caddy obtains/renews real certificates; no disabled TLS checks or self-signed certificate bypasses are used.
- Real authenticated SMTP STARTTLS relay (the supplied configuration targets port 587), permitted sender address and credentials. Mailpit is disabled in production.
- Private external S3 bucket, region, encryption at rest and Block Public Access. Use a workload IAM role or scoped access key. App access should cover bucket listing/HEAD and Get/Put/Delete only under `avatars/`; see `s3-policy.example.json`. No bucket creation/admin privileges are needed. Garage is development-only in this composition.
- Secure secret files, an externally provisioned signing key, off-host backup storage and monitoring ownership.

## Prepare configuration and secrets

```sh
cp .env.production.example .env.production
# Edit only provider/hostname settings; no passwords go in this env file.
python3 scripts/provision-secrets.py
```

The helper prompts privately for SMTP/S3 credentials, generates distinct random DB passwords and a PKCS#8 RSA key, and refuses to overwrite an existing directory. For IAM, S3 credential files are deliberately empty. The sample env file is not deployable as-is: example domains and provider settings must be replaced.

On the production Linux host, application secret files must be readable by UID 999 (the fixed runtime UID). Standalone Compose file-backed secrets retain host file ownership: its `uid/gid` options do not repair permissions for you.

```sh
sudo chown 999:999 secrets/*
sudo chmod 0400 secrets/*
chmod 0700 secrets
```

Docker's host daemon can mount files through the private directory. In managed infrastructure use its secret manager/mount mechanism instead. Do not commit, paste into chat, print in logs or include these files in an image. `secrets/`, `.credentials/`, `.env.*` and `backups/` are excluded from Git/build contexts. Back up the signing identity securely; do not regenerate it on every deploy.

## Validate and deploy

```sh
docker compose -p plot-twist-prod --env-file .env.production \
  -f compose.yaml -f compose.prod.yaml config --quiet

docker compose -p plot-twist-prod --env-file .env.production \
  -f compose.yaml -f compose.prod.yaml up -d --build --wait --wait-timeout 180
```

Only Caddy publishes ports. Gateway is private, trusts forwarded client addresses only from Caddy's fixed private `172.30.0.10` address and forces the HTTPS origin for auth redirects. The edge subnet is `172.30.0.0/24`; if it conflicts, change both the subnet/address and the trusted-proxy file together. Do not add broad trust for arbitrary forwarding headers.

Application containers are non-root/read-only, with temporary storage, bounded memory and restart policy. Startup refuses HTTP issuer, insecure login cookies, development auth switches, missing signing key and unsafe SMTP configuration. The S3 production guard rejects development credentials/plain HTTP endpoints. Actual provider connectivity still has to be checked; a valid YAML/configuration does not establish working SMTP/S3.

Verify externally through HTTPS: registration → receipt of an actual email → verification → OAuth PKCE login → create/update profile → upload/download/delete photo. Check failure behavior and restore procedures in staging first. The private actuator endpoints must stay private.

## Existing development volumes

The default local upgrade preserves the original `auth` DB and creates `profiles`. Old `story`/`character` databases are retained but are no longer used. The API is stateless and no longer connects to a database. Bootstrap grants separate non-superuser roles to auth/domain and transfers existing application-table ownership within their respective DBs.

Do not treat a development DB as a production DB without review. Production refuses the reserved `plot-twist-cli` development client and an enabled `demo` with its known development password. For a deliberate migration, back up first, disable/remove the development identities under an admin session, migrate only intended data and test the new deployment. Never “fix” this by deleting the whole volume or weakening the guards.

Schema migration ownership and runtime DML are currently held by each application role, isolated by database. A stricter managed deployment should run Flyway as a separate migration identity/job and grant runtime only the DML it needs. Future large-table/index migrations need a planned expand/contract or concurrent-index rollout rather than assuming every startup DDL is zero-downtime.

## Backups and restore

Back up **both** PostgreSQL databases, signing keys and the private S3 objects/version history. Database dumps contain personal data and auth state: encrypt them, keep them off-host with restricted access and a defined retention. Do not expose them as CI artifacts.

`python3 scripts/test-backup-restore.py` is a local drill: it dumps both DBs, restores into new random temporary DBs, compares row counts and removes only those temporary DBs. It does not prove S3/key disaster recovery or off-host RPO/RTO. Run this only with the development composition; it uses its local admin client.

For a full restore, test PostgreSQL PITR/snapshots, the matching S3 versions and the signing identity in an isolated staging environment. DB snapshots for different services are not a cross-database distributed snapshot: coordinate writes or design reconciliation for your chosen recovery point. Photo reservations/retirement queues must also be reconciled before enabling writes. Never delete currently ACTIVE objects as a blanket cleanup.

## Metrics, logs, alerts

- Private `/actuator/prometheus`: HTTP latency/status, JVM/pools, `auth_email_outbox_pending`, `auth_email_outbox_dead`, `profile_media_cleanup_pending`.
- `monitoring/prometheus.yml` uses DNS discovery so each API replica is a distinct target. The optional production `monitoring` profile provides loopback-only Prometheus/Grafana with a file-backed admin password and provisioned dashboard; see [monitoring setup](monitoring.md). The public gateway does not expose actuator data. External TLS/SSO, alert delivery and operational ownership remain deployment-specific.
- Set `TRACING_ENABLED=true` and `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` for your collector. Review sampling, redaction and retention before exporting personal-data systems' telemetry. Request bodies/passwords/tokens are not application log fields.
- Configure latency/error SLOs, dependency health, queue age/size, disk capacity, DB connection budget, backup age and certificate expiry alerts. Keep operational access off the public network. Logs rotate locally (3×10 MiB per container); central retention is a separate configuration.

The read benchmark is bounded to at most 40 RPS/60 seconds from one process. It cannot establish high-load capacity or an availability guarantee. Do not bypass rate limits to produce an artificial passing result.

## Failure/release boundaries

JWT revocation is bounded by the access-token lifetime plus configured clock skew: five minutes for machine/native clients, `AUTH_SESSION_DURATION` (four hours by default) for the browser. A stronger requirement needs an explicit revocation/introspection design. Public native clients have no refresh token; renew through PKCE while the login session is valid. CLI logout removes its local token, not every browser login session.

One host, one PostgreSQL instance and one TLS ingress remain single points of failure. Multi-host HA, a managed key rotation policy with overlapping JWKS, real provider validation, load/soak testing and an off-host restore drill are required before claiming their guarantees. The supplied code/config does not claim those operations have happened.

Browser session/access lifetime is configured with `AUTH_SESSION_DURATION=4h` in `.env.production` (range `1m`–`12h`). Recreate auth-service and sign in again to apply it to a new token. See [session semantics and revocation limits](auth.md#продолжительность-браузерной-сессии).

Semantic recommendations require a pinned local model revision, a private Qdrant secret, model-volume backup and derived-index recovery procedures. See [recommendation operations](recommendations.md). First model download needs explicit outbound network access; runtime inference and vector storage are private.
