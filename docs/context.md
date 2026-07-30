# Context — syfo-budstikka replaces esyfovarsel

Orientation and navigation. This file is an **index**, not a decision log. When you need a specific
decision, go to the register; when you need detail, go to the topic document.

## Source priority

Each source owns a different question. Treat a discrepancy as a finding to resolve, not as licence
to pick the convenient source.

| Question | Source |
|---|---|
| What does the system actually do? | Executable code and tests |
| What is binding and hard to reverse? | `docs/adr/NNNN-*.md` |
| What does a domain term mean? | `docs/glossary.md` |
| What does an existing `Bnn` decision mean, and is it still active? | `docs/decisions.md` — the canonical compatibility register for B1-B63 |
| Where does a new decision live? | Relevant ADR for a hard-to-reverse trade-off; issue/plan for a task-scoped choice; topic document for maintained detail |
| How does one area work in detail? | The topic documents below |
| Where are we now, and what is next? | This file |

Load the narrowest source that answers the question. When a `Bnn` is named, look that entry up
directly instead of reading a whole file.

## Navigation

- **`docs/decisions.md`** — canonical compatibility register for B1-B63. Code, ADRs, and topic documents
  reference these by number.
- `docs/glossary.md` - domain vocabulary
- `docs/adr/` - binding architecture decisions
- `docs/kontrakt.md` - channel DTOs and the published Kafka contract
- `docs/datamodell.md` - inbox and delivery
- `docs/ferdigstill.md` - closing and inactivation
- `docs/flyt.md` - end-to-end flow
- `docs/migrering.md` - cutover strategy and the **operational channel map** from esyfovarsel
- `docs/teknologi.md` - technology choices
- `docs/teststrategi.md` - local test and e2e strategy
- `docs/helsesjekk.md` - health checks

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
ikke nye B-numre som standard: vanskelige og overraskende avveininger går i én relevant ADR, mens reversible eller
oppgavespesifikke valg blir i GitHub-sak/plan. Eksisterende B-er kan presiseres eller erstattes, men statusen må stå på
selve oppføringen.

Domeneblindhet (B1/ADR 0001) er den røde tråden: budstikka forgrener aldri på domenetype.

## Hva esyfovarsel er og gjør i dag

Sentral varsel-router for eSyfo. Konsumerer ett topic `team-esyfo.varselbus`, mapper hver hendelse til riktig flate, og
håndterer tilstand rundt utsending, ferdigstilling, retry og fallback til fysisk brev. Kontrakten har
`HendelseType`-varianter for SM_/NL_/AG_-løp.

### Kanaler (flater) ut

- BRUKERNOTIFIKASJON → `min-side.aapen-brukervarsel-v1` (tms varsel-builder)
- DINE_SYKMELDTE → `team-esyfo.dinesykmeldte-hendelser-v2`
- DITT_SYKEFRAVAER → `flex.ditt-sykefravaer-melding`
- ARBEIDSGIVERNOTIFIKASJON → HTTP GraphQL `notifikasjon-produsent-api` (fager) + Altinn
- BREV → HTTP `dokdistfordeling` (journalpostId mottas, budstikka oppretter ikke PDF)
- MIN_SIDE_MICROFRONTEND → `min-side.aapen-microfrontend-v1`

### Nedstrøms-tjenester

pdl-api, digdir-krr-proxy (reservasjon/digital kontakt), syfosmregister (møtebehov-NL,
`SENDT` per virksomhet), narmesteleder, notifikasjon-produsent-api, dokdistfordeling,
istilgangskontroll. `AccessControlService` bygger på **KRR alene** (`kanVarsles`). Resultatet
styrer ekstern varsling på brukervarselet og, i enkelte brevdyktige løp, valget mellom
digitalt varsel og fysisk brev. `SykmeldingService` (syfosmregister) er en SEPARAT og smalere
sjekk: den brukes kun av `MotebehovVarselService.sendVarselTilNarmesteLeder` og gater på om
det finnes en `SENDT` sykmelding for den aktuelle **virksomheten** — ikke en generell
aktiv-sykmelding-gate for alle varsler.

> **Åpent produktvalg:** budstikka gater på død (PDL) og KRR-reservasjon. esyfovarsels smale
> møtebehov-sjekk mot syfosmregister (NL-varsel krever `SENDT` sykmelding for virksomheten) har ingen
> motpart. Se `docs/adr/0009-krr-reservasjon-brevfallback.md`.

### Konsumenter (produsenter inn)

isdialogmote, syfomotebehov, syfooppfolgingsplanservice, isoppfolgingsplan, syfo-oppfolgingsplan-backend,
aktivitetskrav-backend, isarbeidsuforhet, ismanglendemedvirkning, ismeroppfolging, meroppfolging-backend,
isfrisktilarbeid, syfo-dokumentporten. Alle via `team-esyfo.varselbus`.

### Embedded domenekunnskap (det vi vil VEKK fra)

- synligTom-regler per domene (aktivitetskrav +30d, mer veiledning +13u, dialogmøte motetidspunkt)
- microfrontend-livssyklus per domene (åpne/lukke på spesifikke hendelser)
- `VarselTexts.kt` samler mye domenespesifikk kopitekst i appen
- ResendFailedVarslerJob sjekker dinesykmeldte-oppgave ferdigstilt før resend
- AktivitetspliktForhandsvarsel: kjenner sendForhandsvarsel-flagg, brevtype VIKTIG

### Arkitektur i dag

Postgres-tabeller inkluderer `utsendt_varsel`, `utsending_varsel_feilet`, `mikrofrontend_synlighet`,
`arbeidsgivernotifikasjoner_sak`, `arbeidsgivernotifikasjoner_kalenderavtale`, `fodselsdato` og
`planlagt_varsel` (dormant). CronJob (`esyfovarsel-job`) retter feilede og lukker microfronter.
Ingen outbox; Kafka+DB er ikke transaksjonelt. Leader election. Ktor-applikasjon på JVM.

## syfo-budstikka i dag

Ktor-backend med Kafka-konsum, claim/lease-workers, Exposed/Flyway-datamodell og kanaladaptere i aktiv utvikling.
Koden ligger i pakken `no.nav.budstikka`. Se `docs/teknologi.md` for teknologivalg.

## Kjernespenning å designe rundt

Hvor mye domenekunnskap MÅ ligge igjen for å velge kanal/tekst/fallback, og hvordan flytte resten til konsument?
Kontrakt: hva sender domeneappen, hva eier budstikka.
