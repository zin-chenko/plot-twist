# Engineering audit and changes

Scope: existing scaffold → real private-user profile backend and a reviewable production deployment baseline. This is an engineering assessment of this repository, not an assertion that an unspecified infrastructure deployment or high-load target has been validated.

| Finding before | Implemented change / evidence |
| --- | --- |
| No registration or account recovery | Email verification/reset endpoints, transactional SMTP outbox; real HTTP/SMTP integration checks |
| No business data/API | Versioned personal profile CRUD and private photo lifecycle, PostgreSQL migrations and real S3 integration |
| Weak demo assumptions | Production guards, no auto-generated production key, file-backed secrets and disabled demo identities |
| Same DB superuser in all services | Auth/domain separated into non-superuser roles; API DB dependency removed; cross-DB access rejected in tests |
| Concurrent code exchange could succeed four times | DB transaction + row lock + commit-before-response buffer; regression: exactly one success among four concurrent exchanges |
| Passwords/identity lacked a registration policy | Argon2id for new credentials, Unicode length limits, email confirmation, stable UUID identity, lockout/backpressure; legacy BCrypt verification retained |
| No ownership/version semantics | Owner from verified JWT, no writable owner field, ETag/If-Match, parallel update test returns one 200 and one 412 |
| No media persistence/security model | Private S3 bytes + PG metadata, bounded JPEG/PNG decoding, metadata removal, SHA-256 checks, durable reservations and deferred object cleanup |
| Ephemeral auth sessions | JDBC session persistence with migration; reset clears sessions and authorizations |
| Little production visibility/testing | ECS logs, Prometheus/OTLP plumbing, OpenAPI validation, CI, isolated restore and failure tests |
| Old runtime/dependency patch levels | Java 25 LTS, current 3.5-line patch, pinned runtime digests and dependency-update configuration |

## Boundaries retained deliberately

- No public registration UI or SPA; native CLI/standard issuer login and HTTP API are provided.
- No gallery, social login, MFA, account deletion or changing the login email in this version. Deleting a profile removes its personal fields/photo association; the identity account remains.
- No broker dependency is added solely for fashion. SMTP already uses a transactional outbox; `architecture.md` specifies when RabbitMQ/Kafka becomes valuable.
- Production SMTP, DNS/certificates, S3/IAM, secret mounts, off-host backup/restore and multi-host HA require real environment configuration. They are not established by a dry-run Compose validation.
- Current images and functional tests demonstrate the implemented workflow, not an unmeasured capacity ceiling. Run representative load and soak tests against the chosen deployment.
