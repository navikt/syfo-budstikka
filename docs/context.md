# Context — syfo-budstikka replaces esyfovarsel

Orientation and navigation. This file is an **index**, not a decision log or
documentation policy. Source ownership, load order, ADR qualification, and
legacy decision handling live in [`docs/agents/domain.md`](agents/domain.md).

## Navigation

- **`docs/decisions.md`** — frozen compatibility lookup for existing B1–B63
  references
- `docs/glossary.md` - domain vocabulary
- `docs/adr/` - architecture decisions
- `docs/sende-varsler.md` - producer guide and published contract
- `docs/datamodell.md` - inbox and delivery
- `docs/flyt.md` - internal flow and FERDIGSTILL handling
- `docs/migrering.md` - cutover plan from esyfovarsel
- `docs/esyfovarsel-kanalkart.md` - operational legacy channel map
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

Domeneblindhet (ADR 0001) er den røde tråden: budstikka forgrener aldri på domenetype.

### Sendevindu

Dispatch merket `SendingWindow.BUDSTIKKA_OPENING_HOURS` kan gates til budstikkas
sendevindu; ventende opprettelser holdes på inbox (`WAIT` på `inbox_message`,
ADR 0014). Åpningstider og helligdagslogikk eies av koden
(`domain/foundation/calendar/`, `BudstikkaSendingWindowLookup`). Direkte
FERDIGSTILL-kansellering av en ventende OPPRETT er planlagt i #26.
