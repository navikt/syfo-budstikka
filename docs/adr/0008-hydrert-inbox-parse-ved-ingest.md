# ADR 0008 — Hydrated inbox: full parse at ingest, `eventId` only in header

- Status: Decided (supersedes ADR 0002; reverses B54 payload-authoritative rule;
  refines B4/B21, see B61)
- Date: 2026-07-21
- Related: ADR 0002, B4/B10/B18/B21/B22/B43/B54, issue #27

## Context

ADR 0002 deferred payload deserialization to the decision worker to isolate
deduplication from schema. That robustness is now supplied by
`dispatchJson.ignoreUnknownKeys = true` for additive changes and B43 `.v1`/`.v2`
dual-write for breaking changes. A parse failure is therefore usually a real
contract failure and belongs in dead letter.

`FERDIGSTILL` and send-window cancellation need structured `reference` on the
inbox row. Deferring parse either forces parse during closing or adds an
unnecessary hydrated intermediate layer. The contract defines data we care about;
producers own data unknown to the contract.

## Decision

Ingest parses full `Dispatch` envelope and sealed `content`, performing only
syntactic contract validation/hydration. Eligibility, channel choice, fallback,
and `FERDIGSTILL` matching remain in the decision worker.

1. `eventId` exists only in mandatory `DispatchHeader.EVENT_ID`, not payload.
   Envelope is `{ reference, content }`; header is technical dedup/correlation,
   primary-key `ON CONFLICT DO NOTHING`, and missing/invalid header dead-letters
   as `MISSING_EVENT_ID`/`INVALID_EVENT_ID` without payload fallback.
2. Store `content` as `jsonb<DispatchContent>` and remove raw payload text.
   Lift `reference` to its own column for selective `FERDIGSTILL` matching;
   a potential index follows B61's still-open hold-placement decision. Do not lift
   recipient/channel/operation: derive from content. Keep `ignoreUnknownKeys`.
3. Read/deduplicate header ID before parsing, so schema never controls dedup.
   For dead letter store eventId where available; new nullable
   `dead_letter_message.event_id` is null when header is absent.
4. Poison syntactic input (missing/invalid header, empty payload, corrupt JSON,
   missing envelope reference, unknown sealed subtype) dead-letters and commits
   offset. Representable but semantically illegal B21 combinations enter inbox,
   are processed without delivery and counted `ugyldig_kombinasjon`. DB failure
   throws without commit for retry.
5. After parser upgrade, dead-letter unknown-subtype messages may replay from
   Kafka within 90-day B26 retention via offset reset or manual dead-letter
   submission; header dedup makes this safe. Replay is manual, not automatic.
6. `dead_letter_message` holds raw FNR/text payload and must hard-delete around
   100 days (90-day replay plus margin) with B42 periodic deletion, like inbox.
   Keep only raw bytes, Kafka coordinates, and available eventId; never raw-log
   parse failure.
7. B61's hold-placement question remains open. Hydrated inbox makes either
   outbox hold with `CANCELLED` or true pre-send inbox hold cheaper; this ADR does
   not choose one.

## Consequences

Structured `reference` and JSONB content support matching pending rows without
reparse; recipient/channel derive from JSONB. `eventId` has one source, removing
header/body equality risk. `RECEIVED` rows always have parseable content, so
decision-worker `SerializationException` and its raw-PII risk disappear. Poison
does not block partitions; transient faults do not silently lose data.

Real version-skew unknown subtype requires dead-letter and manual replay; B43
sends later breaking changes to `.v2`. Representable illegal combinations still
use B21 decision-worker defense. Unknown additive fields are intentionally not
preserved in JSONB; raw bytes remain only in dead letter. Header is a deliberate
hard producer dependency. PII at rest is in inbox JSONB, delivery recipient,
and dead-letter raw payload; inbox retention stays at least 90 days and dead
letter receives explicit deletion.

## B43 exception

Removing payload `eventId` is breaking on `.v1`, but happens in place because
the service is pre-production with one consumer; dual-write adds no value. This
exception applies only here. Later breaking changes follow B43 `.v2` + dual-write.

## Implementation note (2026-07-21, issue #125; refines decision 3)

`InboxMessageHandler` reads header ID first but parses before
`INSERT ... ON CONFLICT DO NOTHING` (`saveBatch(ignore=true)`). Successful-message
dedup remains schema-independent. A corrupt duplicate of an already ingested ID
dead-letters before primary-key dedup, potentially creating repeated dead-letter
rows with raw FNR. This needs the unlikely producer bug of same ID with different/
corrupt payload; identical Kafka replay is unaffected. Pre-parse DB lookup was
rejected for an extra ingest query; a repeated-dead-letter alert is possible B49
follow-up. B61's before-parse schema-independence applies to success path; dead
letter path parses before primary-key dedup.

## Alternatives considered

Keep ADR 0002 parse-free closing, a separate hydrated table, `reference` Kafka
header, payload-authoritative `eventId`, and dual header/payload equality were
rejected: they either duplicate sources, expand producer headers, move parse
complexity, or add unnecessary layers. One header-only technical ID is cleaner.
