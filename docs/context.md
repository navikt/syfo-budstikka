# Context — syfo-budstikka replaces esyfovarsel

Orientation and navigation. This file is an **index**, not a decision log or
documentation policy. Source ownership, load order, ADR qualification, and
legacy decision handling live in [`docs/agents/domain.md`](agents/domain.md).

## Navigation

- **`docs/decisions.md`** — frozen compatibility lookup for existing B1–B63
  references
- `docs/glossary.md` - domain vocabulary
- `docs/adr/` - architecture decisions
- `docs/kontrakt.md` - channel DTOs and the published Kafka contract
- `docs/datamodell.md` - inbox and delivery
- `docs/ferdigstill.md` - closing and inactivation
- `docs/flyt.md` - end-to-end flow
- `docs/migrering.md` - cutover strategy and the **operational channel map** from esyfovarsel
- `docs/teknologi.md` - technology choices
- `docs/teststrategi.md` - local test and e2e strategy
- `docs/helsesjekk.md` - health checks
- `docs/dead-letter-replay.md` - manual dead-letter replay procedure

## Mål (fra bruker)

syfo-budstikka skal overta for `esyfovarsel`: la domeneapper sende varsler til sykmeldte, nærmeste ledere og
arbeidsgivere **uten** at budstikka bærer domenekunnskap (oppfølgingsplan, dialogmøte, aktivitetskrav osv.). Budstikka
skal kun sørge for riktig kanal på en god måte. Ønsket arkitektur: Kafka, inbox/delivery, asynkron utsending,
idempotens, innebygd retry og feilhåndtering, bedre logging med trace-id/tracing, enklere feilsøk, eget Grafana-board.

## Status

**Design og implementering pågår.** Kode og tester viser nåværende oppførsel; de åpne
[GitHub-sakene](https://github.com/navikt/syfo-budstikka/issues) er den levende arbeidskøen. Ikke bruk denne indeksen
som oppgaveplan.

Domeneblindhet (B1/ADR 0001) er den røde tråden: budstikka forgrener aldri på domenetype.

### Sendevindu (issue #171/#27)

Dispatch kan gates til budstikkas sendevindu: åpent **mandag–lørdag 09–20** (`Europe/Oslo`), stengt søndager og norske
røde dager (påske via Meeus/Jones/Butcher, Kr. himmelfart, pinse, 1. mai, 17. mai, 1.–2. juledag) samt julaften
(24.12). Nyttårsaften (31.12) er **ikke** stengt. Gaten gjelder kun hendelser merket
`SendingWindow.BUDSTIKKA_OPENING_HOURS`. Logikken er en generisk `openingHours { }`-komposisjon i
`domain/foundation/calendar/`, konfigurert i `BudstikkaSendingWindowLookup` og anvendt av `SendingWindowGate`.
Ventende opprettelser holdes på inbox (`WAIT` på
`inbox_message`). Direkte FERDIGSTILL-kansellering av en ventende OPPRETT er
planlagt i #26; inbox-hold er besluttet i ADR 0014.
