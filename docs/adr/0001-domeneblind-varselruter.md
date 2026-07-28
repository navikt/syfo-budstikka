# ADR 0001 — syfo-budstikka as a domain-blind notification router

- Status: Accepted (grilling phase 1)
- Date: 2026-06-29
- Replaces: responsibility in `esyfovarsel`

## Context

`esyfovarsel` is the central eSyfo notification router but carries domain knowledge
from other applications: text catalogue, deadline rules (`synligTom`),
microfrontend lifecycle, and resend logic. This makes it difficult to change and
own. syfo-budstikka must reach recipients (`sykmeldt`, `nærmeste leder`,
`arbeidsgiver`) without knowing their domains.

## Decision

Budstikka is a **domain-blind notification router**. The domain application owns
what and when; budstikka owns delivery.

- Consumers provide final text and explicit expiry (`synligTom`); budstikka owns
  no text catalogue or domain rules.
- One Kafka topic and `Dispatch` envelope serve all recipients. The sealed
  `DispatchContent` subtype encodes channel and operation and carries its typed
  recipient and payload; these are not loose fields or separate topics (B38).
- Producer-provided `eventId` supplies inbox deduplication and `reference` links
  `FERDIGSTILL` to `OPPRETT`; recipient id is the partition key.
- Budstikka owns an always-on eligibility gate (death drops and records; KRR/
  reservation controls only external notification) and delivery robustness.
- Flow is **Inbox → Decision (freeze channel choice) → Delivery**: one row per
  concrete delivery, executed by a claim/lease worker.
- Claim/lease retry treats transient failures as exceptions and permanent failures
  as terminal.

## Consequences

- Budstikka can change without domain knowledge; a new domain needs only a
  consumer with correct channel/text.
- Idempotency and delivery guarantees live structurally in inbox/delivery, not
  ad-hoc cron resend.
- Domain applications own text, expiry, and channel choice.
- Letter fallback for reservation requires `brevFallback` with `journalpostId`.
- Migration from `esyfovarsel` needs coexistence (separate ADR).
