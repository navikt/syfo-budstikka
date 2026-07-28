# Context — syfo-budstikka

`syfo-budstikka` is a domain-blind notification router replacing esyfovarsel.
It receives a dispatch from a domain system and delivers it to the correct
recipient and channel without knowing the business domain. This file is the
short current mental model: an index, not a decision log.

## Sources and precedence

1. [`adr/`](adr/) is binding for hard-to-reverse decisions.
2. [`decisions.md`](decisions.md) defines the active `Bnn` references used in
   code, ADRs, and topic documents. A decision is superseded only where an
   entry or ADR says so explicitly.
3. [`glossary.md`](glossary.md) contains canonical vocabulary.
4. This document describes the current direction and points to detail.
5. The current issue or pull request and task-scoped brief describe active
   work.

## Current system

- **Input:** producers publish `Dispatch { reference, content }` to Kafka.
  `eventId` is required technical metadata in the
  `DispatchHeader.EVENT_ID` header. It provides deduplication and technical
  correlation and is not part of the payload.
- **Processing:** the inbox protects idempotency; workers decide and create
  channel-specific deliveries. The outbox and worker pattern makes sending and
  retry reliable. See [`flyt.md`](flyt.md) and
  [`datamodell.md`](datamodell.md).
- **Boundaries:** Budstikka is domain-blind. `reference` connects create and
  Ferdigstill; `eventId` identifies one event. Use the established terms in
  [`glossary.md`](glossary.md).
- **Platform:** Kotlin/Ktor on Nais with PostgreSQL/Flyway and Kafka. See
  [`teknologi.md`](teknologi.md), and resolve contract or platform changes
  against relevant ADRs before implementation.

## Navigation map

| Area | Read first |
|---|---|
| A concrete `Bnn` reference | The named entry in [`decisions.md`](decisions.md); do not load the whole register |
| Contract, event header, and channels | [`kontrakt.md`](kontrakt.md), [ADR 0008](adr/0008-hydrert-inbox-parse-ved-ingest.md) |
| Flow, inbox, outbox, and retry | [`flyt.md`](flyt.md), [`datamodell.md`](datamodell.md), [ADR 0008](adr/0008-hydrert-inbox-parse-ved-ingest.md), [ADR 0004](adr/0004-konkurrerende-konsumenter-claim-med-lease.md) |
| Domain-blind routing and application layer | [`glossary.md`](glossary.md), [ADR 0001](adr/0001-domeneblind-varselruter.md), [ADR 0003](adr/0003-application-lag-for-use-case-orkestratorer.md) |
| Migration from esyfovarsel | [`migrering.md`](migrering.md) |
| Ferdigstill and inactivation | [`ferdigstill.md`](ferdigstill.md), [`kontrakt.md`](kontrakt.md) |
| Observability and tests | [`teststrategi.md`](teststrategi.md), [ADR 0007](adr/0007-metrikk-som-dispatchmetrics-port.md) |
| KRR reservation and letter fallback | [ADR 0009](adr/0009-krr-reservasjon-brevfallback.md) |
| Build and container | [ADR 0010](adr/0010-container-bygg-med-jib.md) |

## History

Dated status, working instructions, and the legacy-system survey from the former
long context are preserved in [`legacy-context.md`](legacy-context.md) for
historical lookup only.

[ADR 0002](adr/0002-inbox-header-dedup-uten-deserialisering.md) is useful as
history for the earlier parse-free inbox model, but ADR 0008 supersedes it for
the current ingestion and header contract.
