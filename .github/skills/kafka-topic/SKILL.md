---
name: kafka-topic
description: "Use when this Ktor backend is to produce or consume Kafka events — a new or changed consumer/producer, a new topic via Kafkarator, event contract, key strategy, idempotency or DLQ. Triggers: 'listen to a topic' / 'lytte på et topic', 'publish an event' / 'publisere en hendelse', 'new topic' / 'nytt topic', plus 'Kafkarator', 'Topic CRD', 'Rapids & Rivers', '@event_name', 'kafka.pool', 'DLQ', 'idempotent consumer'."
---

# Kafka — topic, consumer and producer

Nav-specific conventions for Kafka in this repository. General Kafka theory is not covered — the focus is topic provisioning, the event contract, and how a consumer/producer is wired into a Ktor app.

Typically used in the calling workflow's design stage when an event contract is being shaped, and in
the implementation stage when a consumer or producer is written. When a lasting decision
passes the ADR gate, recommend the documented route and wait for the user's choice before
`/domain-modeling` records it.

## Detect the existing Kafka style first

**Before you propose code: find out which stack the repository already uses. Follow it. Do not introduce a new style or migrate between them without an explicit mandate.**

In a Ktor backend there are two realistic options:

| Signal in `build.gradle.kts` / code | Stack |
|-------------------------------------|-------|
| `no.nav.helse:rapids-rivers`; `RapidApplication.create(env)`; classes implementing `River.PacketListener` | Rapids & Rivers |
| `org.apache.kafka:kafka-clients`; direct `KafkaConsumer` / `KafkaProducer`, often in a separate thread/coroutine alongside the Ktor server | Plain Apache Kafka |

Spring Kafka (`@KafkaListener`, `KafkaTemplate`) does not belong in a Ktor app — if you see it, the repository either is not a Ktor repository or uses the wrong pattern. Do not propose Spring Kafka here.

Follow the dominant pattern. If the repository has no Kafka yet, choose plain Apache Kafka unless the team is already on Rapids in neighbouring services.

## Approach

1. Check the Nais manifest for `kafka.pool`. The Kafkarator `Topic` CRDs are checked in **here**, in `nais/topics/kafka-dev.yaml` and `nais/topics/kafka-prod.yaml`, and deployed by `.github/workflows/deploy-topic.yaml` — not in a separate `<team>-kafka` repository.
2. Search the codebase for existing consumers/producers and follow the same pattern (startup, error handling, logging).
3. Confirm the stack in `build.gradle.kts` (see the table above).
4. Plan the event contract (topic name, key, fields, the event-id header). Write the
   maintained contract detail that follows from the approved change into the
   relevant topic document. New domain concepts and qualifying lasting decisions are
   candidates for the documented route; wait for the user's choice before
   `/domain-modeling` writes them.
5. Implement according to the pattern for the stack (see the reference files below).
6. Verify with tests (see the reference files) and return the evidence to the calling workflow's verify step.

## Sync vs. event — when to choose what

Nav-specific rules only: periodic batch belongs in a Naisjob (+ Kafka if downstream), and if the team already uses Rapids & Rivers for choreography, publish new events on the shared rapid — do not create a parallel plain producer.

## Kafka in a Ktor app — where does the consumer run?

The Ktor server (`EngineMain` on Netty) and the Kafka consumer are two independent lifecycles in the same process. A `KafkaConsumer.poll` loop must run alongside the HTTP server, not inside a request.

In this repository the lifecycle runs through Ktor DI, not `monitor.subscribe(ApplicationStopPreparing)`: `ConsumerRunner`s are registered in the DI container (`infrastructure/kafka/config/Module.kt`) and started from bootstrap via `startKafkaConsumers()` / `startWorkers()` (`bootstrap/KafkaConsumers.kt`, `bootstrap/Workers.kt`, called in `Application.kt`). Teardown is the DI `.cleanup { }` block, which closes each `ConsumerRunner` / `BackgroundLoop`; `close()` joins the running loop with a 5 s timeout (`CLOSE_TIMEOUT_SECONDS = 5` in `ConsumerRunner.kt`).

Consumer health belongs in **liveness**, not readiness (`docs/helsesjekk.md` lists putting the consumer in the readiness check as an anti-pattern — a dead consumer must not pull the pod out of load balancing). The loop records a self-reported heartbeat every poll round, and the `LivenessCheck` behind `/internal/health/is_alive` (`api/InternalApi.kt`, `infrastructure/Health.kt`) reports unhealthy only when the last poll is stale. Never ping the broker in a probe. Keep `/internal/*` (health probes, metrics) outside auth, cf. `/auth-overview`.

## NAIS Kafka configuration

```yaml
# nais/*.yaml (excerpt)
spec:
  kafka:
    pool: nav-dev   # or nav-prod
```

NAIS injects SSL env vars into the pod — this repository reads them via `${?ENV_VAR}` substitution in HOCON `application.conf` into the typed Kafka config (`infrastructure/kafka/config/Config.kt`), not via `System.getenv`:

- `KAFKA_BROKERS` — bootstrap servers
- `KAFKA_TRUSTSTORE_PATH` / `KAFKA_KEYSTORE_PATH` — PKCS12 files
- `KAFKA_CREDSTORE_PASSWORD` — password for both
- `KAFKA_SCHEMA_REGISTRY*` — only if schema registry is enabled

## Topic provisioning with Kafkarator

Topics at Nav are created declaratively via Kafkarator `Topic` CRDs — not through code or `kubectl` by hand.

```yaml
apiVersion: kafka.nais.io/v1
kind: Topic
metadata:
  name: <team>.<domain>.v<version>
  namespace: <team>
  labels:
    team: <team>
spec:
  pool: nav-prod
  config:
    retentionHours: 168          # 7 days
    retentionBytes: -1           # no limit
    cleanupPolicy: delete        # or "compact" for state topics
    minimumInSyncReplicas: 2
    partitions: 3
    replication: 3
  acl:
    - team: <team>
      application: <app>
      access: readwrite          # read | write | readwrite
    - team: <other-team>
      application: <consumer-app>
      access: read
```

Important choices:

- **cleanupPolicy: compact** for topics that represent the latest state per key. Requires a stable key.
- **partitions**: increase early — reducing the partition count requires a new topic. Start with 3–6 for domain events.
- **acl**: explicit per consumer app — no wildcards.

## Nav event design (stack-agnostic)

Applies whether you use plain Kafka or Rapids.

### Topic naming

```
<team>.<domain>.v<version>

teamsykefravar.rapid.v1            # Rapids & Rivers shared topic
teamsykefravar.sykmelding.v1       # Domain events
teamsykefravar.oppfolging.v1       # Domain events
```

### Key strategy

- **User/entity ID as the key** → events for the same entity land on the same partition → ordering is preserved per entity.
- `fnr`, `aktørId`, `sykmeldingId`, `vedtakId` are typical keys.
- Do not use a random UUID as the key unless you deliberately want an even partition spread without an ordering guarantee.

### Event naming and content

- **Past tense + snake_case**: `sykmelding_sendt`, `oppfolging_opprettet`, `vedtak_fattet` — not `create_x` / `process`.
- **Events are facts**, not commands. Describe what happened.
- **Event identity lives in a Kafka header, not the payload.** This repository's contract puts the event UUID in the `DispatchHeader.EVENT_ID` header (kontrakt module); the consumer reads it with `readEventId()` in `InboxMessageHandler.kt` and dead-letters records where it is missing or invalid. Per the README, `eventId` exists only in the header and is what deduplication keys on — not a payload field, and not the message key.
- The `@`-prefixed payload metadata (`@event_name`, `@id`, `@created_at`, ...) is a Rapids & Rivers convention. This repository does not use it — it applies only when working on that stack (see `references/rapids-and-rivers.md`).
- **No PII without a deliberate assessment.** Fødselsnummer as the key is acceptable on Nav-internal topics, but never log it, and consider encrypting sensitive free-text fields.

## Idempotency

In this repository deduplication keys on the event-id Kafka header (`DispatchHeader.EVENT_ID`) — never the payload, the message key, or the Kafka offset. The header UUID is the primary key of `inbox_message`, and `saveBatch` inserts with ON CONFLICT DO NOTHING semantics (`InboxMessageRepositoryImpl.kt`, `batchInsert(ignore = true)`), so a redelivered record is a no-op.

## Dead-letter handling

Messages that can never be processed (missing/invalid event-id header, missing or unparseable payload) must **not block the stream** — distinguish temporary errors (throw, let Kafka redeliver) from permanent ones (park, continue).

This repository has **no DLQ topic and no DLQ producer**: poison records are parked as Postgres rows in `dead_letter_message` (`DeadLetterMessageRepository.kt`), written by `InboxMessageHandler` while the offset commits and the stream moves on. Replay is a manual procedure: `DeadLetterReplayer` runs at startup only when `DEAD_LETTER_REPLAY_ENABLED=true`, re-parses the rows in code, and inserts the parseable ones into `inbox_message` — see `docs/dead-letter-replay.md`. Follow this pattern; do not introduce a DLQ topic here.

Alternative for repos without a database only: a DLQ topic per domain (`<team>.<domain>.dlq.v1`) with a small producer of your own, alerting on the DLQ rate. Reach for that only when there is no Postgres to park rows in.

## Event evolution

Breaking event changes are a coordination problem with consuming teams —
the same discipline as API versioning, see `/api-design`. Review Nav-wide and
team consequences with `/architecture-review`. When the decision qualifies,
recommend the documented route and wait for the user's choice before `/domain-modeling`
records an ADR.

## Stack-specific patterns

Read only the one relevant to the repository:

- **Plain Apache Kafka** (Kotlin consumer/producer in Ktor, SSL config, commit strategy, Testcontainers): [`references/plain-kafka.md`](references/plain-kafka.md).
- **Rapids & Rivers** (River setup, validate/demand/require, publishing, TestRapid): [`references/rapids-and-rivers.md`](references/rapids-and-rivers.md).

## Boundaries

### Always
- Follow the stack the repository already uses.
- Create topics via Kafkarator `Topic` CRDs — never ad hoc in code or `kubectl`.
- Explicit ACL per consumer app.
- Topic names `<team>.<domain>.v<version>`; event names in past tense + snake_case.
- Idempotent consumption (dedup on the `DispatchHeader.EVENT_ID` header).
- Dead-letter permanent errors (Postgres `dead_letter_message` rows here), alert on the rate.
- Structured logging with `event_id` / `correlation_id` — never PII (`fnr`) in logs.
- `kafka.pool` set in the Nais manifest before deploy.

### Ask first
- Migration plain ↔ Rapids.
- Changing the consumer group — the default `syfo-budstikka-budstikka-v1` in `application.conf`, overridable via `KAFKA_BUDSTIKKA_GROUP_ID` (triggers reprocessing from `auto.offset.reset`).
- A breaking event change that other teams consume.
- Changing `partitions` / `cleanupPolicy` on an existing topic.

### Never
- Log national identity numbers or other PII.
- Use the Kafka offset as an idempotency key.
- Run the `poll` loop inside an HTTP handler.
- Silently swallow permanent errors so that the stream stops.

NAIS docs: https://doc.nais.io/persistence/kafka/
