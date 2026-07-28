# ADR 0002 — Inbox deduplication on Kafka header, raw payload without deserialization

- Status: Superseded by ADR 0008 (hydrated inbox, parse at ingest), 2026-07-21.
  Originally decided for B54, issue #19; retained as history.
- Date: 2026-07-08
- Related: ADR 0001, decisions B54 and B26 in `docs/decisions.md`

## Context

Budstikka consumes `team-esyfo.budstikka.v1` and must deduplicate idempotently
(B4), because replay within the bounded 90-day Kafka retention (B26) can
otherwise send the same notification twice. `eventId` existed in the
authoritative payload envelope and mirrored Kafka header
`DispatchHeader.EVENT_ID`. Parsing at ingest couples deduplication to payload
schema and lets unrelated schema change or `UGYLDIG_JSON` block it. Consumers
must distinguish retryable transient failure from permanent poison failure.

## Decision

Ingest performs **no body parsing**:

1. Read `eventId` from the Kafka header for fast-path dedup; later worker
   validation requires `payload.eventId == header.eventId`.
2. Store raw byte-exact payload as `text` in `inbox_message` without deserializing.
3. Defer `reference` until the worker decodes `content`.
4. Deduplicate through `event_id` primary key (`insertIgnore`/`ON CONFLICT DO NOTHING`).
5. Missing/invalid `event_id` header or empty payload is poison: dead-letter to
   `DeadLetterMessageTable` and commit offset. DB outage is transient: throw so
   `ConsumerRunner` does not commit and retries with backoff.

## Consequences

Dedup/ingest survives payload schema change; poison does not block a partition;
transient fault does not silently lose data; dead-letter is separate from inbox
persistence. Header/body truth exists twice, so mismatch is poison. A missing
header yields `MISSING_EVENT_ID` even with valid body. Replay remains safe only
while `event_id` inbox rows are retained at least 90 days.

## Alternatives considered

Payload parsing at ingest was rejected because it couples dedup to schema.
Dead-lettering transient failures was rejected because it loses data. Throwing on
poison was rejected because one corrupt message blocks its partition.
