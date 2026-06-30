# CONTEXT — syfo-budstikka skal erstatte esyfovarsel

## Mål (fra bruker)
syfo-budstikka skal overta for `esyfovarsel`: la domeneapper sende varsler til
sykmeldte, nærmeste ledere og arbeidsgivere **uten** at budstikka bærer
domenekunnskap (oppfølgingsplan, dialogmøte, aktivitetskrav osv.). Budstikka skal
kun sørge for riktig kanal på en god måte. Ønsket arkitektur: Kafka, inbox/outbox,
asynkron utsending, idempotens, innebygd retry og feilhåndtering, bedre logging med
trace-id/tracing, enklere feilsøk, eget Grafana-board.

## Hva esyfovarsel er og gjør i dag
Sentral varsel-router for eSyfo. Konsumerer ett topic `team-esyfo.varselbus`,
mapper hver hendelse til riktig flate, og håndterer tilstand rundt utsending,
ferdigstilling, retry og fallback til fysisk brev. 25 `HendelseType` (SM_/NL_/AG_).

### Kanaler (flater) ut
- BRUKERNOTIFIKASJON → `min-side.aapen-brukervarsel-v1` (tms varsel-builder)
- DINE_SYKMELDTE → `team-esyfo.dinesykmeldte-hendelser-v2`
- DITT_SYKEFRAVAER → `flex.ditt-sykefravaer-melding`
- ARBEIDSGIVERNOTIFIKASJON → HTTP GraphQL `notifikasjon-produsent-api` (fager) + Altinn
- BREV → HTTP `dokdistfordeling` (journalpostId mottas, budstikka oppretter ikke PDF)
- MIN_SIDE_MICROFRONTEND → `min-side.aapen-microfrontend-v1`

### Nedstrøms-tjenester
pdl-api, digdir-krr-proxy (reservasjon/digital kontakt), syfosmregister (aktiv sm),
narmesteleder, notifikasjon-produsent-api, dokdistfordeling, istilgangskontroll.
Kanalvalg via AccessControlService (KRR + sm-register) → digital ellers brev.

### Konsumenter (produsenter inn)
isdialogmote, syfomotebehov, syfooppfolgingsplanservice, isoppfolgingsplan,
syfo-oppfolgingsplan-backend, aktivitetskrav-backend, isarbeidsuforhet,
ismanglendemedvirkning, ismeroppfolging, meroppfolging-backend, isfrisktilarbeid,
syfo-dokumentporten. Alle via `team-esyfo.varselbus`.

### Embedded domenekunnskap (det vi vil VEKK fra)
- synligTom-regler per domene (aktivitetskrav +30d, mer veiledning +13u, dialogmøte motetidspunkt)
- mikrofrontend-livssyklus per domene (åpne/lukke på spesifikke hendelser)
- VarselTexts.kt: all kopitekst hardkodet i appen
- ResendFailedVarslerJob sjekker dinesykmeldte-oppgave ferdigstilt før resend
- AktivitetspliktForhandsvarsel: kjenner sendForhandsvarsel-flagg, brevtype VIKTIG

### Arkitektur i dag
Postgres 17, tabeller: utsendt_varsel, utsendt_varsel_feilet, mikrofrontend_synlighet,
arbeidsgivernotifikasjoner, fodselsdato, planlagt_varsel (dorment). CronJob
(esyfovarsel-job) retter feilede + lukker mikrofronter. Ingen outbox; Kafka+DB ikke
transaksjonelt. Leader election. Ktor 3.4, Kotlin 2.3, JVM 21.

## syfo-budstikka i dag
Tomt Ktor-skjelett (Main, Routing="Hello World", app.yaml). JVM 25, Ktor 3.5.1,
ktlint. Ingen DB/Kafka/auth/nais ennå. Pakke no.nav.syfo.

## Låste beslutninger
- B1: Konsument eier tekst + utløp (synligTom). Budstikka kjenner ikke domenet.
- B2: Konsument oppgir mottaker (SM/NL/AG) + tillatte kanaler. Budstikka gjør KRR/digital-sjekk og velger digital vs. brev + fallback.
- B3: 1 Kafka-topic `team-esyfo.varselbestilling` (IKKE «varselbus» — det var esyfovarsel-navnet), enkelthendelse (ingen liste), gjenbrukt for alle mottakere. Mottaker + handling er FELT, ikke topics. Ferdigstilling = egen hendelse. Brev kan ikke ferdigstilles.
- B4: Produsent oppgir eventId (inbox-dedup) + referanse (kobler FERDIGSTILL→OPPRETT). Budstikka kjenner ikke domenet, matcher kun på referanse.
- B5: Partisjonsnøkkel = mottakerens id (SM=fnr, NL=nl-fnr, AG=orgnr). Ingen antakelse om at alt handler om en sykmeldt — bygg fleksibelt.
- B6: Eksplisitt kanal pr. hendelse + kanalspesifikt payload (sealed/typet). Ingen array av kanaler. Vil konsument ha flere kanaler → flere hendelser.
- B7: Alltid-på eligibility-gate i budstikka: død (PDL) → dropp + egen DB-tabell + Grafana-metrikk; reservert (KRR) → styrer kun ekstern varsling.
- B8: Brukernotifikasjon har valgfritt nøstet `brevFallback`-objekt: tilstedeværelse = send brev ved reservasjon, og objektet bærer påkrevd journalpostId (typesikker validering). `eksternVarsling: Boolean` for SMS/e-post.
- B9: Mottaker er kanalspesifikk (modell A), med value-class `Personident`/`Orgnummer` som maskerer fnr i logg (toString="***"). Ikke delt sealed-hierarki.
- Begreper: Reservasjon (KRR) styrer kun ekstern varsling; brukervarsel vises på Min side uansett. Mikrofrontend er i scope (brukerkommunikasjon).
- B10: Tre faser — (1) Inbox: dedup på eventId, ingen logikk; (2) Beslutning: eligibility-gate (død/KRR) resolveres og FRYSES til konkrete leveranser; (3) Outbox: én rad pr. konkret leveranse, worker utfører + retryer «dumt». Transaksjon: eksterne lesekall (KRR/PDL) først, så én DB-tx (skriv outbox + marker inbox behandlet).
- B11: Retry sentralt — eksponentiell backoff + jitter m/ tak; sentral maxLeveringsalder → UTLOPT + metrikk/alert (aldri stille dropp); synligTom (når oppgitt) kapper fristen tidligere; transient (retry) vs permanent (rett til feilet) skilles.
- B12: Prosesseringstopologi — decoupled workers. Konsument skriver kun til inbox (rask, idempotent). Egen beslutnings-worker + outbox-worker drevet av kontinuerlig polling med DB-radlås (FOR UPDATE SKIP LOCKED), så flere podder deler last uten dobbeltlevering. Ingen leader election.

## Datamodell-beslutninger (se docs/DATAMODELL.md)
- B13: Topologi A — to tabeller: `inbox_hendelse` + `leveranse`. `referanse` + `inbox_event_id` på leveranse holder døren åpen for senere varsel-aggregat (B) via additiv migrering.
- B14: Status på leveranse-raden (KLAR/SENDT/FEILET_PERMANENT/UTLOPT); transient feil = bli i KLAR m/ backoff (forsok, neste_forsok_tid). Ingen separat feilet-tabell.
- B15: Outbox-worker holder radlås under selve sendingen (FOR UPDATE SKIP LOCKED) med stramme klient-timeouts. Lease/claim er ren oppgraderingssti senere.
- B16: Idempotensnøkkel mot kanaler = vår genererte `leveranse.id` (UUID), gjenbrukt ved retry + FERDIGSTILL. `ekstern_respons_id` (nullable) lagrer kanalens retur-id for sporing.
- B17: Sporing — `trace_id`-kolonne på inbox+leveranse; enkelt-id spores i Grafana via Loki (logger) + Tempo (traces) korrelert på trace_id. Prometheus-metrikker kun lav kardinalitet (kanal/status/mottaker_type/feiltype), aldri id/fnr som label.
- B18: Inbox-livsløp — konsument skriver MOTTATT; beslutnings-workeren setter BEHANDLET/DROPPET(drop_aarsak=DOD)/FEILET i én tx. Inbox har egen backoff (forsok/neste_forsok_tid) for transiente gate-feil mot PDL/KRR. Død-dropp logges som status på inbox (justerer B7: ingen egen tabell). PII-at-rest: fnr i klartekst i mottaker_id (CloudSQL-kryptert, maskert i logg); retensjon/GDPR er åpent punkt.

## Kjernespenning å designe rundt
Hvor mye domenekunnskap MÅ ligge igjen for å velge kanal/tekst/fallback, og hvordan
flytte resten til konsument? Kontrakt: hva sender domeneappen, hva eier budstikka.
