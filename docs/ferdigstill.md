# FERDIGSTILL flow — syfo-budstikka

How budstikka closes or inactivates a previously sent notification without domain knowledge.
Derived from B3, B4, B6, and B19–B21.

## Principle

FERDIGSTILL is a **separate event** on the same contract and topic as OPPRETT, following
the **same flow and delivery machinery**. A closure is simply a `delivery` row with
`operation=INACTIVATE`: it is picked up through claim/lease and is idempotent through its
own `delivery.id`, just like a send.

## Targeting (B19, B38)

- FERDIGSTILL is **typed per closable channel** (B38): the channel is implicit in its
  type (`BrukervarselInactivate`, `LedervarselInactivate`, `DittSykefravaerInactivate`,
  `ArbeidsgivervarselInactivate`). The event is thin: `referanse` plus a typed **key**.
- The key is typed (`PersonIdentifier`/`Orgnummer`), preserving PII masking (B9) and
  making illegal `(channel, key)` pairs unrepresentable.
- For matching, budstikka finds the open OPPRETT delivery by
  `(referanse, recipient_id, kanal)`, where `recipient_id` is the matching key: the
  **OPPRETT partition anchor** known by the consumer. It is the sykmeldt fnr for
  BRUKERVARSEL/LEDERVARSEL/DITT_SYKEFRAVAER (B24: sykmeldt, not NL fnr), and orgnr for
  ARBEIDSGIVERVARSEL. A resolved NL fnr or `ekstern_respons_id` belongs in the payload
  or dedicated columns and **never** participates in matching.
- To close several channels, a consumer sends one FERDIGSTILL per channel. Budstikka
  never decides scope or fan-out itself, so it remains domain-blind.

## The close operation derives from the stored row (B39)

A FERDIGSTILL event never carries message type, path, or operation. The decision worker
(B28) finds the matching OPPRETT delivery; `decide()` freezes closure parameters
(`meldingstype`, NL/Altinn path, `ekstern_respons_id`, `grupperingsid`) onto the
INACTIVATE delivery; and `ChannelHandler` (B27) dispatches using those **stored technical
attributes**, never a domain type (B1/B30). This is compatible with sticky ownership
(B34): budstikka receives FERDIGSTILL only for OPPRETT it created, so the stored row
always exists.

## Edge handling (B20)

| Situation | Action |
| --- | --- |
| OPPRETT found, `SENT` | Normal path: create `delivery(operation=INACTIVATE)`; the outbox closes it on the channel. |
| OPPRETT found, still `READY` (not sent) | The current model has no separate `CANCELLED` state. OPPRETT and FERDIGSTILL are separate delivery rows in the same claim/lease flow. |
| No matching OPPRETT | Not a hard error: inbox → `PROCESSED`, no delivery row, log and metric `ferdigstill_uten_treff`. Partition ordering makes “OPPRETT arrives later” unlikely when both are sent. |

## Closability per channel

| Channel | Closable? | INACTIVATE mechanism |
| --- | --- | --- |
| Min side brukervarsel | Yes | Publish an inactivate event (tms varsel, same varselId = `delivery.id`). |
| Dine Sykmeldte (NL) | Yes | FERDIGSTILL event on the dinesykmeldte topic. |
| Ditt Sykefravær | Yes | Close or replace message. |
| AG-notifikasjon (+Altinn) | Yes | Derived from the stored row (B39): OPPGAVE→`oppgaveUtført`, BESKJED→`hardDelete`, sak→`nyStatusSak(FERDIG)`. |
| Fysisk brev | **No** | Cannot be withdrawn (B3). |
| Microfrontend | Separate visibility toggle | Not a FERDIGSTILL channel. Use the B41 `MicrofrontendEnable`/`MicrofrontendDisable` pair for `(person, microfrontendId)`. |

## Illegal combinations (B21)

- Make illegal combinations (for example FERDIGSTILL + BREV) **unrepresentable** in
  the typed contract (sealed types) and JSON Schema on the topic contract. Producers
  then fail at build or validation time, not in production.
- Runtime is **defence in depth**: if an invalid combination nevertheless reaches the
  inbox (schema drift, old producer), mark it `PROCESSED`, create no delivery row, and
  log plus emit `ugyldig_kombinasjon`. No alert storm and no `FAILED`.

## Kafka semantics (B21)

The consumer validates the mandatory header and parses the full `Dispatch` at ingest.
Syntactically valid events are persisted as hydrated inbox rows; poison input is persisted
as dead letter. The offset is committed only after the complete poll batch has been
handled successfully. Eligibility, matching, and other business decisions happen later
in the decision worker against the database row, detached from Kafka. A terminal database
status (`FAILED`/`DROPPED`/invalid) **never blocks the partition** and creates no
redelivery loop.
