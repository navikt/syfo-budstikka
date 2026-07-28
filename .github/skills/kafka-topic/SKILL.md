---
name: kafka-topic
description: "Design and implement Kafka integrations. Use when creating or changing topics, consumers, producers, event contracts, keys, idempotency, DLQs, or Kafkarator resources."
---

# Kafka — topics, consumers, and producers

Nav-specific conventions for topic provisioning and event contracts in this Ktor
repository. Use it during Grillmester phases 1–2 when shaping a contract and
during implementation when writing a consumer or producer.

## First, find the repository’s Kafka stack

**Before proposing code, find and follow the existing style.** Do not introduce
a new stack or migrate between stacks without an explicit request.

| Signal in `build.gradle.kts` / code | Stack |
|---|---|
| `no.nav.helse:rapids-rivers`, `RapidApplication.create(env)`, `River.PacketListener` | Rapids & Rivers |
| `org.apache.kafka:kafka-clients`, direkte `KafkaConsumer` / `KafkaProducer` | Plain Apache Kafka |

Spring Kafka does not belong in a Ktor application. If the repository has no
Kafka yet, choose plain Apache Kafka unless the team already uses Rapids in
neighbouring services.

## Workflow

1. Check the NAIS manifest’s `kafka.pool`, Kafkarator `Topic` CRDs, and existing
   consumer/producer patterns in code.
2. Confirm the stack in `build.gradle.kts`, then follow its startup, error-handling,
   and logging conventions.
3. Clarify topic name, key, event fields, and `@event_name`. Record difficult domain
   choices as ADRs; refine terms in `docs/glossary.md`, and update `docs/context.md`
   only when the mental model or pointer map changes.
4. Provision the topic declaratively with Kafkarator and explicit ACLs, never from
   code or manual `kubectl`.
5. Implement for the selected stack and verify with tests. Return command, result,
   and exit code in KOKK_RESULT/PR.

## Non-negotiable contract

- Topic names use `<team>.<domain>.v<version>`; event names are past-tense snake_case
  facts, never commands.
- Use an entity’s stable ID as the key to preserve ordering per entity. Never use a
  Kafka offset as an idempotency key; deduplicate on stable `@id`.
- Consume idempotently. Permanent failures go to a DLQ with rate-based alerting;
  transient failures must be retryable. Never log `fnr` or other PII.
- A `poll` loop runs beside the Ktor server and stops with the application, never in
  an HTTP handler. Consumer health contributes to `/internal/health/is_alive`; change
  readiness semantics explicitly if needed.
- Set `kafka.pool` in the NAIS manifest before deployment.

## Read as needed

- [Event design, synchronous vs. event, idempotency, and DLQ](references/event-design.md)
- [Ktor lifecycle and NAIS-injected Kafka configuration](references/ktor-lifecycle.md)
- [Kafkarator Topic-CRD](references/kafkarator-topic.md)
- [Event evolution](references/event-evolution.md)
- [Plain Apache Kafka in Ktor](references/plain-kafka.md)
- [Rapids & Rivers in Ktor](references/rapids-and-rivers.md)

## Boundaries

### Always

- Follow the repository’s existing stack and create topics through Kafkarator `Topic` CRDs.
- Set an explicit ACL per consumer application and use structured logging with
  `event_id` / `correlation_id` without PII.
- Put breaking event changes and consumer-group changes before the team.

### Ask first

- A plain ↔ Rapids migration, changed `KAFKA_CONSUMER_GROUP_ID`, or breaking event change.
- Changed `partitions` or `cleanupPolicy` on an existing topic.

### Never

- Log PII, use an offset as an idempotency key, or run `poll` in an HTTP handler.
- Silently swallow permanent failures and stall the stream.

NAIS documentation: https://doc.nais.io/persistence/kafka/
