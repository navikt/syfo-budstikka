---
name: triage
description: "Bruk når innkommende issues/bug-meldinger på navikt/syfo-budstikka skal vurderes og klargjøres: klassifiser, verifiser påstanden, avklar, prioriter, gjør AFK-klar. Triggere: 'triage', 'gå gjennom innboksen', 'se på #42', 'er denne buggen ekte'. Ikke for å bryte en ferdig plan i issues (se /to-issues)."
---

# triage

Flytt innkommende issues (og eksterne PR-er) på `navikt/syfo-budstikka` gjennom en liten tilstandsmaskin: **klassifiser → verifiser → grill ved behov → skriv en arbeidsklar brief**. Fokuset her er **vurdering og klargjøring**, ikke oppretting.

Dette komplementerer to nabo-skills — ikke dupliser dem:
- `/to-issues` bryter en *ferdig plan* i nye vertikale snitt. Triage tar *innkommende* saker og avgjør om/hvordan de skal jobbes.
- `/issue-management` eier selve oppretting-, type-, label- og board-mekanikken (issue-typer, sub-issues, avhengigheter, prosjektboard, ferdigmelding). Slå opp der — `issue-management/references/issue-types.md` for type/label og `issue-management/references/projects.md` for board — når du faktisk setter en rolle eller status. Gjenta ikke mekanikken her.

En **PR er et issue med kode**: samme roller, samme tilstander. Der noe avviker for en PR er det merket "for en PR" under. En bar `#42` slås opp som issue eller PR.

Hver kommentar du poster under triage **må** starte med:

```
> *Generert av AI under triage.*
```

## Referansedokumenter

- [AGENT-BRIEF.md](AGENT-BRIEF.md) — hvordan skrive en durabel, arbeidsklar brief

## Roller

To **kategori**-roller (mapper til GitHub issue-type, se `/issue-management`):

- `bug` — noe er ødelagt (issue-type `Bug`)
- `enhancement` — ny funksjonalitet eller forbedring (issue-type `Feature`/`Story`/`Task`)

Fem **tilstands**-roller (GitHub-labels):

- `needs-triage` — må vurderes
- `needs-info` — venter på mer info fra melder
- `ready-for-agent` — fullt spesifisert, klar for en AFK-agent
- `ready-for-human` — krever menneskelig implementasjon (skjønn, ekstern tilgang, designvalg, manuell testing)
- `wontfix` — gjøres ikke

For en PR leses tilstandene mot den vedlagte koden: `ready-for-agent` betyr at en brief er vedlagt og en agent tar neste steg på diffen; `ready-for-human` betyr klar for et menneske å merge.

Hver triagert sak skal ha **én** kategori-rolle og **én** tilstands-rolle. Konflikterer tilstandene, flagg det og spør før du gjør noe annet. De faktiske label-strengene i repoet kan avvike fra de kanoniske navnene over — sjekk eksisterende labels.

**Tilstandsoverganger:** en utriagert sak går normalt til `needs-triage` først; derfra til `needs-info`, `ready-for-agent`, `ready-for-human` eller `wontfix`. `needs-info` returnerer til `needs-triage` når melder svarer. Overganger som ser uvanlige ut: flagg og spør først.

## Påkalling

Brukeren påkaller `/triage` og beskriver i naturlig språk. Tolk og handle. Eksempler:

- "Vis meg alt som trenger oppmerksomhet"
- "La oss se på #42" (issue eller PR)
- "Flytt #42 til ready-for-agent"
- "Hva er klart for agenter å plukke?"

## Vis hva som trenger oppmerksomhet

Spør issue-trackeren og presenter tre bøtter, eldst først:

1. **Utriagert** — aldri vurdert.
2. **`needs-triage`** — vurdering pågår.
3. **`needs-info` med melder-aktivitet siden siste triage-notat** — må revurderes.

Inkluder eksterne PR-er i bøttene og merk hver linje `[PR]` eller `[issue]`. Discovery viser kun *eksterne* PR-er (en kollegas pågående PR er ikke triage-arbeid) — men en eksplisitt navngitt PR triageres alltid uansett forfatter. Vis antall og en énlinjes oppsummering per sak. La brukeren velge.

## Triage en konkret sak

### 1. Hent kontekst
Les hele saken (body, kommentarer, labels, forfatter, datoer; for en PR også diffen). Parse tidligere triage-notater så du ikke spør om det som allerede er løst. Utforsk kodebasen:

- Følg den smale lasterekkefølgen i `docs/agents/domain.md`: les bare relevante topic-dokumenter og ADR-er, glossaret for domenespråk, og `docs/context.md` når saken faktisk krever repository-orientering eller overordnet status.
- Kjør to sjekker: **(a) redundans** — søk etter eksisterende implementasjon
  av ønsket oppførsel *etter domenebegrep* (ikke bare meldingens ordlyd), og
  rapporter hvor du lette. Finnes den → allerede-implementert `wontfix` (steg
  5). **(b) tidligere avvist** — søk etter konseptet i lukkede GitHub-issues og
  `wontfix`-saker. Les lukkediskusjonen og behandle saken som tidligere avvist
  bare når begrunnelsen faktisk sier det; en levert eller duplisert sak er
  tidligere arbeid, ikke en avvisning.

### 2. Anbefal
Si din kategori- og tilstands-anbefaling med begrunnelse, pluss en kort kodebase-oppsummering relevant for saken — inkludert om den allerede er implementert. Vent på retning.

### 3. Verifiser påstanden
Før noen grilling: sjekk at påstanden holder.
- **Bug** — reproduser fra meldingens steg. Bruk `/diagnosing-bugs` for å bygge et stramt rødt signal (`./gradlew test`, en feilende route-kall, en Kafka-melding som ikke konsumeres idempotent, en Flyway-feil). Er symptomet et runtime-/NAIS-problem (pod-krasj/OOMKilled, 401/403, consumer lag, DB-timeout), følg det diagnostiske treet der.
- **PR** — bekreft at diffen gjør det den hevder: sjekk ut grenen, kjør `./gradlew test` og relevante kommandoer.
- Rapporter: bekreftet (med kodevei), feilet, eller utilstrekkelig detalj (et sterkt `needs-info`-signal). En bekreftet verifisering gir en mye sterkere brief.

### 4. Grill (ved behov)
Trenger saken kjøtt på beina, kjør `/grilling` og grill den i form ett spørsmål
av gangen. Når avklarte begreper eller varige beslutninger bør dokumenteres,
anbefal dokumentert løp, forklar hvorfor og vent på brukerens valg. Bruk
`/nav-architecture-review` for NAV-spesifikke funn; etter valgt dokumentert løp
eier `/domain-modeling` glossaret, ADR-gaten og selve ADR-en.

### 5. Bruk utfallet
- `ready-for-agent` — post en arbeidsklar brief ([AGENT-BRIEF.md](AGENT-BRIEF.md)). Saken regnes nå som plukkbar i @grillmester sin faseløkke.
- `ready-for-human` — samme struktur som en agent-brief, men noter *hvorfor* den ikke kan delegeres.
- `needs-info` — post triage-notat (mal under).
- `wontfix` — lukk, med kommentar avhengig av *hvorfor*:
  - **Allerede implementert** — endringen finnes alt. Pek på hvor den lever i koden.
  - **Avvist (bug)** — høflig forklaring, så lukk.
  - **Avvist (enhancement)** — skriv en selvstendig begrunnelse i
    lukkekommentaren, så lukk. Det lukkede issuet er historikken; opprett ikke
    et eget beslutningsarkiv i repoet.
- `needs-triage` — sett rollen. Valgfri kommentar hvis det er delvis fremgang.

## Rask tilstandsoverstyring

Sier brukeren "flytt #42 til ready-for-agent", stol på det og sett rollen direkte. Bekreft hva du er i ferd med å gjøre (rolleendringer, kommentar, lukking), så handle. Hopp over grilling. Flytter du til `ready-for-agent` uten en grilling-økt, spør om de vil ha en brief skrevet.

## Mal for needs-info

```markdown
> *Generert av AI under triage.*

## Triage-notat

**Det vi har etablert så langt:**

- punkt 1
- punkt 2

**Dette trenger vi fortsatt fra deg (@melder):**

- spørsmål 1
- spørsmål 2
```

Fang alt som ble løst under grilling under "etablert så langt" så arbeidet ikke går tapt. Spørsmål skal være konkrete og handlingsrettede — ikke "vennligst gi mer info".

## Gjenoppta en tidligere økt

Finnes tidligere triage-notater på saken, les dem, sjekk om melder har svart på utestående spørsmål, og presenter et oppdatert bilde før du fortsetter. Ikke spør om det som allerede er løst.
