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
| What was decided, and is it still active? | `docs/decisions.md` — the canonical `Bnn` register |
| How does one area work in detail? | The topic documents below |
| Where are we now, and what is next? | This file |

Load the narrowest source that answers the question. When a `Bnn` is named, look that entry up
directly instead of reading a whole file.

## Navigation

- **`docs/decisions.md`** — canonical active register, B1-B63. Code, ADRs, and topic documents
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

**Design og implementering pågår.** Beslutninger festes som nummererte B-er i `docs/decisions.md` og
utdypes i temadokumentene.


**Designområder:** 1 Datamodell ✅ · 2 FERDIGSTILL ✅ · 3 Kanal-DTO-er ✅ (AG B29–B33; Inaktiver-typing B38–B39;
tekstmodell/enums + microfrontend B40–B41) · 4 Observability ✅ (B17 + B45–B49: korrelasjon=eventId, logging/PII,
metrikk-katalog, endepunkter, varsling) · 5 Auth & ACL 🔶 (M2M-mønster låst — PDL/dokdist/KRR via Texas; KRR-reservasjon
B62/ADR 0009; resten av ACL detaljeres per kanal ved implementering) · 6 Migrering ✅ (B34–B37, detaljer ved
implementering) · 7 Lokal test/e2e ✅ (B50–B53: delt substrat i `src/test`, prod-grense via build, Testcontainers,
port-fakes).

**Neste konkrete steg:** kontrakten er ferdig-spekket, GDPR/retensjon avklart (B42), topic-identitet/navn låst (B43:
`team-esyfo.budstikka.v1`, rot-type `Dispatch`), teknologivalg låst (B44), observability ferdig-grillet (B45–B49) og
lokal test/e2e-strategi låst (B50–B53). Gjenstår kun å grille område 5 (Auth & ACL — TokenX/Azure AD, accessPolicy;
ventet rett-fram da esyfovarsel har Texas-mønsteret). Epic + sub-issues for utvikling er opprettet på
`navikt/syfo-budstikka`
(kontrakt, datamodell, worker-topologi og retensjon er implementeringsklare).

**Arbeidsmåte:** grill én beslutning av gangen (anbefalt alt først), grunn i research ved usikkerhet, fest durable
beslutninger i `docs/decisions.md` med nye B-nummer, commit per ferdig delområde. Domeneblindhet (B1) er den røde tråden:
budstikka forgrener aldri på domenetype.
## Hva esyfovarsel er og gjør i dag

Sentral varsel-router for eSyfo. Konsumerer ett topic `team-esyfo.varselbus`, mapper hver hendelse til riktig flate, og
håndterer tilstand rundt utsending, ferdigstilling, retry og fallback til fysisk brev. 25 `HendelseType` (SM_/NL_/AG_).

### Kanaler (flater) ut

- BRUKERNOTIFIKASJON → `min-side.aapen-brukervarsel-v1` (tms varsel-builder)
- DINE_SYKMELDTE → `team-esyfo.dinesykmeldte-hendelser-v2`
- DITT_SYKEFRAVAER → `flex.ditt-sykefravaer-melding`
- ARBEIDSGIVERNOTIFIKASJON → HTTP GraphQL `notifikasjon-produsent-api` (fager) + Altinn
- BREV → HTTP `dokdistfordeling` (journalpostId mottas, budstikka oppretter ikke PDF)
- MIN_SIDE_MICROFRONTEND → `min-side.aapen-microfrontend-v1`

### Nedstrøms-tjenester

pdl-api, digdir-krr-proxy (reservasjon/digital kontakt), syfosmregister (aktiv sm), narmesteleder,
notifikasjon-produsent-api, dokdistfordeling, istilgangskontroll. Kanalvalg via AccessControlService (KRR +
sm-register) → digital ellers brev.

> **Åpent produktvalg:** budstikka gater på død (PDL) og KRR-reservasjon, men **ikke** på aktiv sykmelding.
> Om det er tilsiktet domeneblindhet er behandlet i `docs/adr/0009-krr-reservasjon-brevfallback.md`.

### Konsumenter (produsenter inn)

isdialogmote, syfomotebehov, syfooppfolgingsplanservice, isoppfolgingsplan, syfo-oppfolgingsplan-backend,
aktivitetskrav-backend, isarbeidsuforhet, ismanglendemedvirkning, ismeroppfolging, meroppfolging-backend,
isfrisktilarbeid, syfo-dokumentporten. Alle via `team-esyfo.varselbus`.

### Embedded domenekunnskap (det vi vil VEKK fra)

- synligTom-regler per domene (aktivitetskrav +30d, mer veiledning +13u, dialogmøte motetidspunkt)
- microfrontend-livssyklus per domene (åpne/lukke på spesifikke hendelser)
- VarselTexts.kt: all kopitekst hardkodet i appen
- ResendFailedVarslerJob sjekker dinesykmeldte-oppgave ferdigstilt før resend
- AktivitetspliktForhandsvarsel: kjenner sendForhandsvarsel-flagg, brevtype VIKTIG

### Arkitektur i dag

Postgres 17, tabeller: utsendt_varsel, utsendt_varsel_feilet, microfrontend_synlighet, arbeidsgivernotifikasjoner,
fodselsdato, planlagt_varsel (dorment). CronJob (esyfovarsel-job) retter feilede + lukker microfronter. Ingen outbox;
Kafka+DB ikke transaksjonelt. Leader election. Ktor 3.4, Kotlin 2.3, JVM 21.

## syfo-budstikka i dag

Ktor-backend med Kafka-konsum, claim/lease-workers, Exposed/Flyway-datamodell og kanaladaptere i aktiv utvikling. JVM
25, Ktor 3.5.1, ktlint. Pakke `no.nav.budstikka`.

## Kjernespenning å designe rundt

Hvor mye domenekunnskap MÅ ligge igjen for å velge kanal/tekst/fallback, og hvordan flytte resten til konsument?
Kontrakt: hva sender domeneappen, hva eier budstikka.
