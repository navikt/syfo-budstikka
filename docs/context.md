# Context — syfo-budstikka replaces esyfovarsel

Orientation and navigation. This file is an **index**, not a decision log. When you need a specific
decision, go to the register; when you need detail, go to the topic document.

## Source priority

Each source owns a different question. Treat a discrepancy as a finding to resolve, not as licence
to pick the convenient source.

| Question | Source |
|---|---|
| What does the system actually do? | Executable code and tests |
| What is binding and hard to reverse? | The status semantics in `docs/agents/domain.md`, then the relevant `docs/adr/NNNN-*.md` |
| What does a domain term mean? | `docs/glossary.md` |
| What does an existing `Bnn` decision mean, and is it still active? | `docs/decisions.md` — the canonical compatibility register for B1-B63 |
| Where does a new decision live? | ADR only when the three-part gate passes; issue/plan for a task-scoped choice; topic document for maintained detail |
| How does one area work in detail? | The topic documents below |
| Where are we now, and what is next? | This file |

Load the narrowest source that answers the question. When a `Bnn` is named, look that entry up
directly instead of reading a whole file.

## Navigation

- **`docs/decisions.md`** — canonical compatibility register for B1-B63. Code, ADRs, and topic documents
  reference these by number.
- `docs/glossary.md` - domain vocabulary
- `docs/adr/` - status-tagged architecture decisions; interpretation and lifecycle are owned by `docs/agents/domain.md`
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

`docs/decisions.md` bevarer de eksisterende B1–B63-referansene som et kompatibilitetsregister. Nye, varige valg får
ikke nye B-numre som standard: `/domain-modeling` avgjør om de kvalifiserer til
én relevant ADR. Reversible eller oppgavespesifikke valg blir i
GitHub-sak/plan. Eksisterende B-er kan presiseres eller erstattes, men statusen
må stå på selve oppføringen.

Domeneblindhet (B1/ADR 0001) er den røde tråden: budstikka forgrener aldri på domenetype.

### Sendevindu (issue #171/#27)

Dispatch kan gates til budstikkas sendevindu: åpent **mandag–lørdag 09–20** (`Europe/Oslo`), stengt søndager og norske
røde dager (påske via Meeus/Jones/Butcher, Kr. himmelfart, pinse, 1. mai, 17. mai, 1.–2. juledag) samt julaften
(24.12). Nyttårsaften (31.12) er **ikke** stengt. Gaten gjelder kun hendelser merket
`SendingWindow.BUDSTIKKA_OPENING_HOURS`. Logikken er en generisk `openingHours { }`-komposisjon i
`domain/foundation/calendar/`, konfigurert i `BudstikkaSendingWindowLookup` og anvendt av `SendingWindowGate`
(ADR 0011, 0012, 0013). Ventende leveranser holdes på inbox (`WAIT` på `inbox_message`), avgjort i ADR 0014 (#166);
FERDIGSTILL kansellerer en ventende OPPRETT direkte.
