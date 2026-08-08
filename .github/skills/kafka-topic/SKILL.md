---
name: kafka-topic
description: "Use when this Ktor backend (no.nav.syfo) is to produce or consume Kafka events — a new or changed consumer/producer, a new topic via Kafkarator, event contract, key strategy, idempotency or DLQ. Triggers: 'listen to a topic', 'publish an event', 'Kafkarator', 'Topic CRD', 'consumer', 'producer', 'Rapids & Rivers', 'River', '@event_name', 'kafka.pool', 'DLQ', 'idempotent consumer'."
---

# Kafka — topic, consumer and producer

Nav-specific conventions for Kafka in this repository. General Kafka theory is not covered — the focus is topic provisioning, the event contract, and how a consumer/producer is wired into a Ktor app.

Typically used in @grillmester phases 1–2 when an event contract is being shaped, and in
the implementation phase when a consumer or producer is written. When a lasting decision
passes the ADR gate, recommend the documented track and wait for the user's choice before
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

1. Check the Nais manifest for `kafka.pool` and whether Kafkarator `Topic` CRDs exist (often in a separate `<team>-kafka` repository).
2. Search the codebase for existing consumers/producers and follow the same pattern (startup, error handling, logging).
3. Confirm the stack in `build.gradle.kts` (see the table above).
4. Plan the event contract (topic name, key, fields, `@event_name`). Write the
   maintained contract detail that follows from the approved change into the
   relevant topic document. New domain concepts and qualifying lasting decisions are
   candidates for the documented track; wait for the user's choice before
   `/domain-modeling` writes them.
5. Implement according to the pattern for the stack (see the reference files below).
6. Verify with tests (see the reference files) and return the evidence to @grillmester phase 5.

## Sync vs. event — when to choose what

| Need | Pattern | When |
|------|---------|------|
| An answer is needed immediately, the call must visibly succeed or fail | REST on a Ktor route | CRUD, lookups, user interaction |
| Fire-and-forget notification, audit, async downstream | Kafka producer (plain) | Notifications, logging, processes that can wait |
| Event choreography across many services | Rapids & Rivers on a shared rapid topic | Saga flows, multi-service workflows |
| Periodic batch | Naisjob (+ Kafka if downstream) | Nightly jobs, reports, reprocessing |

If the team already uses Rapids for choreography, publish new events there — do not create a parallel plain producer.

## Kafka in a Ktor app — where does the consumer run?

The Ktor server (`EngineMain` on Netty) and the Kafka consumer are two independent lifecycles in the same process. A `KafkaConsumer.poll` loop must run alongside the HTTP server, not inside a request. Start and stop it together with the application:

```kotlin
fun Application.kafkaConsumerModule(consumer: HendelseConsumer) {
    val job = launch(Dispatchers.IO) { consumer.run() }   // own coroutine, not in a route
    monitor.subscribe(ApplicationStopPreparing) {
        consumer.stop()                                    // set running=false, let the loop finish
        job.cancel()
    }
}
```

Expose the consumer's health in `/internal/isready` so that the pod is not marked ready before the consumer is actually polling. Keep `/internal/*` (isalive, isready, metrics) outside auth, cf. `/auth-overview`.

## NAIS Kafka configuration

```yaml
# nais/*.yaml (excerpt)
spec:
  kafka:
    pool: nav-dev   # or nav-prod
```

NAIS injects SSL env vars into the pod — read them in Ktor via `System.getenv(...)` or `environment.config`:

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
- **partitions**: increase early — scaling down requires a new topic. Start with 3–6 for domain events.
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
- **Standard metadata** in the payload:
  - `@event_name` — event type
  - `@id` — unique UUID per event (used for idempotency)
  - `@created_at` — ISO-8601 timestamp
  - `@produced_by` — producing service
  - `@correlation_id` — propagate from the incoming request (strongly recommended)
- **No PII without a deliberate assessment.** Fødselsnummer as the key is acceptable on Nav-internal topics, but never log it, and consider encrypting sensitive free-text fields.

## Idempotency

Kafka delivers at-least-once — duplicates happen. Consumers must be idempotent. Deduplicate on a stable event ID (`@id` in the payload), never on the Kafka offset (it changes on repartitioning).

```kotlin
fun prosesser(eventId: String, /* ... */) {
    if (eventStore.alleredeProsessert(eventId)) return
    // process ...
    eventStore.markerProsessert(eventId)
}
```

The dedup table is typically a Postgres table — add it as a Flyway migration.

## Dead-letter handling (concept)

Messages that can never be processed (corrupt payload, permanent validation error) must **not block the stream**.

1. **Distinguish temporary from permanent errors.** Temporary (network, DB down) → throw an exception, let Kafka retry. Permanent → log + DLQ, continue.
2. **A DLQ topic** per domain (`<team>.<domain>.dlq.v1`) with the original message + error cause + timestamp.
3. **Alert on the DLQ rate**, not on individual messages.
4. **Manual replay** after a bugfix: read the DLQ, republish to the original topic.

The implementation follows the stack — in a Ktor repository you usually roll a small DLQ producer of your own. Follow the pattern that already exists.

## Event evolution

```
How do you change an existing event?
├── Add a new field (optional)
│   └── Backwards compatible. Consumers must tolerate unknown fields
│       (tolerant parsing / interestedIn), not require them.
│
├── Change a field format (breaking)
│   └── New topic version v2. Dual-write from the producer.
│       Migrate consumers one at a time. Stop v1 production last.
│
├── Remove a field
│   └── 1. Verify that no consumer requires the field.
│       2. Remove it from the producer. 3. Wait + monitor before topic cleanup.
│
└── New event type
    └── Publish with a new @event_name. Existing consumers ignore
        unknown event_names (applies especially to Rapids).
```

Breaking event changes are a coordination problem with consuming teams —
the same discipline as API versioning. Review Nav-wide and
team consequences with `/architecture-review`. When the decision qualifies,
recommend the documented track and wait for the user's choice before `/domain-modeling`
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
- Idempotent consumption (dedup on `@id`).
- DLQ for permanent errors, alert on the DLQ rate.
- Structured logging with `event_id` / `correlation_id` — never PII (`fnr`) in logs.
- `kafka.pool` set in the Nais manifest before deploy.

### Ask first
- Migration plain ↔ Rapids.
- Changing `KAFKA_CONSUMER_GROUP_ID` / consumer group (triggers reprocessing from `auto.offset.reset`).
- A breaking event change that other teams consume.
- Changing `partitions` / `cleanupPolicy` on an existing topic.

### Never
- Log fødselsnummer or other PII.
- Use the Kafka offset as an idempotency key.
- Run the `poll` loop inside an HTTP handler.
- Silently swallow permanent errors so that the stream stops.

NAIS docs: https://doc.nais.io/persistence/kafka/
