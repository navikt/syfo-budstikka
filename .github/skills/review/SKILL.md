---
name: review
description: "Brukes når du skal gå gjennom DIN EGEN diff før du ber om en fersk grill-inspektor-review eller åpner PR: en uforpliktet endring, en branch mot main, eller diffen siden et fast punkt skal granskes for korrekthet, regresjon, kanttilfeller og scope. Trigget når du nettopp har skrevet ferdig kode i implementer-fasen, eller når noen sier 'selvreview', 'gå gjennom egen diff', 'review før PR', 'review siden X'."
---

# Selvreview — gå gjennom egen diff før andre øyne

Gransk **din egen** endring inline i verifiseringsfasen før PR og eventuell
`grill-inspektor`-review. Rydd bort åpenbare funn før du ber om ferske øyne.

## Les diffen, ikke minnet

Spor endret atferd fra teksten og kallflyten, ikke fra det du husker at du ville
oppnå. Et grønt testpass beviser bare at testene du skrev passerer; selvreview
leter etter det de ikke dekker.

## 1. Fest punktet og hent diffen

Fast punkt er det brukeren oppga — commit-SHA, branch, tag, `main`, `HEAD~5`. Mangler det, spør. Verifiser at det resolver, og at diffen ikke er tom, FØR du går videre:

```bash
git rev-parse <fast-punkt>                 # resolver referansen?
git diff <fast-punkt>...HEAD --stat        # tre-prikk = mot merge-base
git log <fast-punkt>..HEAD --oneline       # commits i scopet
```

Bruk **tre-prikk** (`...HEAD`) så du sammenligner mot felles forfar, ikke mot en branch som har drevet videre. Hold deg til ett diff-scope gjennom hele reviewen.

## 2. Seks akser — hold dem adskilt

Gå gjennom diffen langs seks akser. **Ikke slå dem sammen.** En endring kan passere én akse og stryke på en annen (korrekt kode som implementerer feil krav; rett krav implementert i strid med repoets konvensjoner). Slår du dem sammen, maskerer den ene den andre.

### A. Korrekthet
Gjør hver endret linje det den ser ut til å gjøre? Les kontrollflyten, ikke kommentarene. Av-for-én, invertert betingelse, feil variabel, glemt `return`, suspenderende kall uten `await`/coroutine-scope, `runBlocking` i en hot path, ressurs som ikke lukkes (`use {}`).

### B. Regresjon
Hva ELLERS treffer den endrede koden? Følg kallere oppover: endret du en delt funksjon, en route-handler, en serialiseringsmodell, et DTO som også brukes av en annen konsument? Endret signatur eller default-verdi som stille endrer atferd for eksisterende kallere? Sjekk om en eksisterende test burde ha fanget endringen — gjorde den ikke det, mangler dekning.

### C. Kanttilfeller
Tom liste, `null`, manglende felt, samtidighet, retry, timeout. NAV/Ktor-spesifikt — se sjekklisten under.

### D. Scope (diff-disproporsjon)
Er det noe i diffen som oppgaven IKKE ba om? Refaktorering snikinnført i en feilretting, urelatert formattering, en «mens jeg var her»-endring. Hver hunk skal kunne spores til oppgaven/issuet eller `PLAN.md`. Det motsatte også: ba oppgaven om noe som ikke er i diffen?

### E. Krav-dekning (mot spec)
Sammenlign diffen mot oppgaven/issuet, `.grill/PLAN.md` og eksplisitt relevante topic-dokumenter. For hvert krav: innfridd / delvis / mangler. For hver ADR som rører området: følger koden beslutningen, eller avviker den stille? Et stille ADR-avvik er en blocker, ikke en detalj.

### F. Standard-dekning (mot repoets konvensjoner)
Følger koden måten dette repoet skriver kode på — Ktor-route-struktur, feilkontrakt via StatusPages, DI-mønster, navngiving, pakkestruktur under `no.nav.syfo`? Hopp over alt verktøy håndhever (formattering, import-orden) — det fanges av gatene, ikke av øynene dine.

## NAV/Ktor-kanttilfeller (akse C, sjekkliste)

- **Auth:** Manglende eller feil claim-sjekk? `azp` validert mot `AZURE_APP_PRE_AUTHORIZED_APPS`? NAVident/`pid` hentet trygt (ikke `!!` på en claim som kan mangle)? Route bak riktig `authenticate("…")`-gren?
- **PII i logger:** Sniker fnr, navn, diagnose eller sykmeldingsstatus seg inn i en standard-logglinje via string-interpolasjon? Visning av persondata til ansatt → CEF-auditlog, ikke standardlogg. (Detaljer: `/security-review`.)
- **Kafka:** Tom/`null` record-value? Idempotens ved redelivery? Manuell vs. auto-commit-semantikk? Poison-message — havner den i DLQ eller looper den? Offset committet før eller etter sideeffekt?
- **Postgres/Flyway:** Ny migrering — kjørbar fremover OG bakoverkompatibel med kjørende pods (rolling deploy)? `NOT NULL`-kolonne uten default på en ikke-tom tabell? N+1 introdusert? Connection lukket / returnert til pool? Transaksjonsgrense rundt fler-stegs skriving?
- **Ktor HTTP:** Feil mappet til riktig status via StatusPages (ikke lekket stacktrace til klient)? `Nav-Call-Id` propagert til utgående kall? Timeout og retry på eksterne kall? Paginering bevart?
- **NAIS:** Endret `accessPolicy.inbound` speilet i auth-koden, og omvendt? `outbound` mot riktig cluster/namespace? Ny env-variabel faktisk satt i manifestet?
- **Coroutines:** Blokkerende kall i en suspend-funksjon? Manglende `CoroutineScope`/structured concurrency? Exception svelget i en launch?

## 3. Fiks, deretter lever til ferske øyne

Selvreview produserer en handling, ikke en rapport til arkivet:

1. **Funn du kan fikse nå → fiks dem inline.**
2. **Kjør de deterministiske gatene på nytt** etter fiks — `./gradlew test` (og `build`/lint der det finnes). Hardt pass/fail, med ferskt bevis i samme melding. Ingen «ser bra ut» uten kommando + output + exit-kode.
3. **Funn du bevisst lar stå** (utenfor scope, egen oppgave) → noter dem kort i `.grill/STATE.md` så de ikke forsvinner.
4. **Beslutningskandidater** som dukker opp under reviewen → rapporter dem.
   Når en kandidat passerer ADR-gaten, anbefal dokumentert løp og vent på
   brukerens valg før `/domain-modeling` skriver.

Når stegene er fullført, rapporter at diffen er klar for eventuell
`grill-inspektor`-review. `@grillmester` håndterer risikovurderingen,
brukerens valg og reviewartefakten.

## Flytkobling

- **Fase i faseløkka:** verifiser (fase 5), før eventuell inspektørreview.
- **Leser:** oppgaven/issuet, `.grill/PLAN.md`, eksplisitt relevante topic-dokumenter og ADR-er, samt `.grill/STATE.md`. Les `docs/context.md` bare ved behov for orientering eller overordnet status.
- **Dybde:** bruk relevant domeneskill når et funn krever spesialisert review.
