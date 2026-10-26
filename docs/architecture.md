# Architecture decisions and messaging

There is no single “big-tech architecture”. The useful practices here are explicit ownership, bounded resources, measurable behavior, standard protocols and tests of failures. The profile domain currently needs a private relational model and object storage, not a large fleet of independently deployed services.

## Decisions implemented

1. **Three bounded services.** Auth owns identity. Domain owns profiles and media metadata. API is the requested stateless entry/application boundary and has no database. Nginx combines public routing with API load balancing. Keep business rules in domain; do not spread profile writes across services.
2. **PostgreSQL + S3.** Small structured personal data and versions live in PostgreSQL. Binary images live in a private object store; storing avatars as database BLOBs or adding a document database is unnecessary for this model. Garage is a local S3-compatible implementation; production uses an external private S3 service and scoped IAM credentials.
3. **MVC/JDBC with virtual threads.** Java 25 LTS keeps straightforward synchronous transaction boundaries. Blocking I/O is suitable for virtual threads; CPU image decoding and crypto work remain explicitly bounded. An end-to-end reactive rewrite is not required to gain concurrency.
4. **Versioned contracts and concurrency.** OpenAPI 3.1 and `/api/v1`. SQL ownership filters plus verified account IDs. Strong ETags prevent lost updates and make retries conditional. DTO allowlists prevent mass assignment. Errors use Problem Details; authentication failures use standard HTTP 401/403.
5. **Cross-store consistency.** Photo upload reserves a durable PENDING metadata row before touching S3, then atomically activates the reference with the profile version. Failed/residual uploads are garbage-collected. Replacement retires the old object with a grace period for in-flight readers. This is a small compensation workflow, not a distributed ACID transaction with S3.
6. **Reliable side effects.** Registration and its email intent commit together. An outbox worker uses `FOR UPDATE SKIP LOCKED`, bounded SMTP timeouts and backoff. Delivery is at-least-once; a crash after SMTP acceptance can duplicate an email. Verification/reset codes are single-use, so duplicate delivery cannot redeem twice.
7. **Operational boundaries.** Bounded pools and upload concurrency, private dependencies, non-root/read-only application containers, pinned runtime images, ECS logs, Prometheus metrics, optional OTLP traces, CI and DB restore drills. Limits are starting budgets, not proven maximum capacity.

Spring Boot stays on the current patch of the existing 3.5 line to isolate the registration/security work from a simultaneous Security 7/Jackson 3 migration. Java is upgraded to 25 LTS. The next major framework upgrade should be a dedicated migration verified by the same protocol, ownership and concurrency tests.

## RabbitMQ: recommended first broker if notifications grow

Useful concrete jobs:

- email confirmation/reset/notification delivery in a separately scaled worker;
- image moderation, thumbnail variants and other slow processing;
- bounded retry queues and operational dead-letter handling.

The API should persist state and an outbox intent in one DB transaction. A publisher sends the event with a stable `eventId`, waits for publisher confirm and only then marks it published. If it crashes between confirm and DB commit, it republishes: the consumer must deduplicate by event ID. Consumers acknowledge **after** durable completion, use bounded backoff and a DLQ; do not use an infinite immediate nack/requeue loop.

For a reliable cluster, consider quorum queues, multiple nodes and tested failover. That is an operational investment; a single Rabbit container does not make the system highly available. Email codes need short retention and restricted queue access, not an unrestricted analytics topic.

Today the PostgreSQL email outbox already delivers reliably without a broker. Add Rabbit when independently scaling workers, queue routing/backpressure and shared retry/DLQ operations become useful. It is the better first fit for this product's notification tasks.

## Kafka: useful for a durable profile event stream

Example events: `AccountVerified`, `ProfileUpdated`, `PhotoActivated`. Independent consumers could update a search index, an analytical warehouse and an audit projection. Kafka is useful when multiple consumers must progress independently, replay history or rebuild projections, and when ordered per-account streams matter.

Use `accountId` as the partition key, include `eventId`, `schemaVersion`, `occurredAt` and `profileVersion`, and make consumers idempotent. A stale event must not overwrite a newer profile version. Use versioned JSON initially; Avro/Protobuf plus a schema registry becomes useful when independently evolving teams need compatibility enforcement.

Publish from a transactional outbox (polling first, or Debezium CDC when justified). Do not perform “UPDATE DB; send Kafka” as an uncoordinated dual write. CDC additionally requires logical replication configuration, replication privileges, monitoring lag and WAL retention. Kafka transactions do not make an external SMTP/S3 side effect exactly-once.

Keep full names, birth dates, raw tokens, passwords and image bytes out of the general event stream. Consumers should receive only the fields they need under explicit retention/access rules.

| Requirement | Prefer |
| --- | --- |
| Emails and routed background jobs with retries | RabbitMQ |
| Several independent consumers, long-lived history and replay | Kafka |
| One small service with a few reliable side effects | Existing PostgreSQL outbox |

## When photo processing becomes asynchronous

For large files, video or expensive moderation, change the contract deliberately: create an upload reservation, upload directly to a private quarantine prefix with a short-lived signed URL, enqueue processing and return 202 with an operation ID. Publish a processed image only after validation, and compare the expected profile version before activation. Keep the old photo usable while the new one is pending. The current synchronous avatar API is intentionally bounded to 5 MiB/16 MP and returns completion only after S3 and metadata activation.

## Capacity and deployment

Define target traffic and SLOs first. Measure API p95/p99/error rates, CPU, heap, DB connections, SQL plans, SMTP outbox age and S3 latency. A short read benchmark is a regression signal, not proof of capacity. Budget Hikari connections across every replica; API has no pool. Scale API through Nginx; domain/auth scaling must also address their load balancing, keys, startup coordination and shared state.

For a multi-host deployment, choose the platform before generating Kubernetes/Helm/Terraform scaffolding. Typical next pieces are an external HA load balancer, managed PostgreSQL with PITR, managed S3, a secret manager and centralized telemetry. Network policies and mTLS become relevant as service/network trust boundaries expand. Every additional infrastructure component needs an owner, failure tests and an operational purpose.

References: [Rabbit publisher confirms/acknowledgements](https://www.rabbitmq.com/docs/confirms), [Kafka delivery semantics](https://kafka.apache.org/documentation/#semantics), [Debezium outbox router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html).
