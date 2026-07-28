---
description: "Covers synchronous-vs-event choices, Nav event design, idempotency, and DLQs. Read when shaping a Kafka contract, key strategy, or failure handling."
---

# Event design — Kafka

## Synchronous versus event-driven

| Need | Pattern | When |
|---|---|---|
| An immediate answer and visible failure are required | REST Ktor route (see `/api-design`) | CRUD, lookup, user interaction |
| Fire-and-forget notification, audit, asynchronous downstream work | Plain Kafka producer | Notification, logging, work that can wait |
| Choreography across several services | Rapids & Rivers on a shared rapid topic | Saga flow, multi-service workflow |
| Periodic batch | Naisjob, with Kafka when needed | Nightly jobs, reports, reprocessing |

If the team already uses Rapids for choreography, publish new events there
instead of creating a parallel plain producer.

## Topic, key, and event payload

```
<team>.<domain>.v<version>

teamsykefravar.rapid.v1            # shared Rapids & Rivers topic
teamsykefravar.sykmelding.v1       # domain event
teamsykefravar.oppfolging.v1       # domain event
```

- Use a user or entity ID as key so events for the same entity land on the same
  partition and retain ordering. `fnr`, `aktørId`, `sykmeldingId`, and `vedtakId`
  are typical domain keys.
- Do not use a random UUID as key unless even partition distribution without an
  ordering guarantee is a deliberate choice.
- Event names are past-tense snake_case, such as `sykmelding_sendt` or
  `vedtak_fattet`. Events are facts, never commands.
- Standard metadata is `@event_name`, a unique UUID in `@id`, ISO-8601
  `@created_at`, `@produced_by`, and preferably propagated `@correlation_id`.
- Do not add PII to a payload without deliberate consideration. A national ID as
  a key can be acceptable on Nav-internal topics but must never be logged; consider
  encrypting sensitive free text.

## Idempotency and DLQ

Kafka delivers at least once. Deduplicate on stable event ID (`@id`), never on
offset; a deduplication table is normally a Flyway-migrated Postgres table (see
`/flyway-migration`).

```kotlin
fun prosesser(eventId: String, /* ... */) {
    if (eventStore.alleredeProsessert(eventId)) return
    // prosesser ...
    eventStore.markerProsessert(eventId)
}
```

Transient failures, such as network or database outage, must throw an exception
so Kafka retries. Permanent failures, such as corrupt payload or lasting
validation error, must be logged and sent to `<team>.<domain>.dlq.v1` with the
original message, failure reason, and timestamp before the stream continues.
Alert on DLQ rate, and replay manually to the original topic after a fix.

Breaking event changes require coordination with consuming teams: see
[event evolution](event-evolution.md) and record the decision as an ADR.
