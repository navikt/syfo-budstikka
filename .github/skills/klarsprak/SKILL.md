---
name: klarsprak
description: Brukes når norsk tekst i syfo-budstikka skal skrives eller vaskes — feilmeldinger og API-feilrespons, loggmeldinger, README, ADR-er i docs/adr/, PR-beskrivelser, release-notes — eller når brukeren ber om klarspråk, språkvask, fjerning av AI-markører, retting av anglisismer, eller hjelp med norsk/engelsk terminologivalg i Ktor-backenden (pakke no.nav.syfo).
---
# Klarspråk — norsk teknisk skriving for Ktor-backend

Retningslinjer for norsk bokmål i feilmeldinger, loggtekst, API-respons, dokumentasjon og PR-beskrivelser i denne Ktor-backenden. Basert på Språkrådets klarspråk-prinsipper, ISO 24495-1, Digdirs klarspråk-veileder og Navs språkprofil. Språkloven pålegger Nav klart, korrekt språk tilpassa mottakeren — det gjelder også tekst som lekker ut til sluttbruker via API-feil og logger som havner i Kibana.

Organisasjonen skrives **Nav**, ikke «NAV» (unntak: `NAIS`, `NAVident`-claim, `no.nav.syfo`) — se norwegian-text-instruksjonen for regelen.

## Når du skal bruke skillen

- Feilmeldinger og API-feilrespons (det frontend eller en annen tjeneste faktisk får tilbake)
- Loggmeldinger (skal være entydige og søkbare i Kibana — ikke svulstige)
- README, ADR-er (`docs/adr/`) og annen Markdown i repoet
- PR-beskrivelser, release-notes og commit-meldinger
- Terminologivalg når norsk og engelsk fagspråk blandes i Kotlin-kode og dokumentasjon
- Rensing av AI-markører og anglisismer i tekst du eller en agent har generert

## Fagtermer

### Alltid engelsk (ikke oversett)

Infra/plattform: image, cluster, node, container, deployment, release, namespace, pod, secret, NAIS, rollback, failover, backup, health check, readiness, liveness.

Git/PR-flyt: pull request, merge, commit, branch, rebase, hotfix, bugfix, patch.

Backend/Ktor-spesifikt: endpoint, route, payload, request, response, suspend, coroutine, Flow, scope, audience, claim, token, JWT, connection pool, offset, topic, consumer, producer, migration, schema.

Generelt: edge case, bug, middleware, pipeline, runtime, framework.

### Norsk er OK for

feilsøking, oppgradering, sikkerhetskrav, vedlikehold, tilgjengelighet, kodegjennomgang, avhengighet, kø, melding, kvittering, validering, oppslag.

### Sammensatte ord med engelske termer

Bruk bindestrek: `Kafka-topicet`, `deploy-steg`, `token-validering`, `Flyway-migrasjon`, `GitHub-repoet`, `health-endepunktet`. Ikke særskriv: «Postgres operatoren» og «Kafka consumer» (som norsk frase) er feil — skriv `Postgres-operatoren`, `Kafka-consumeren`.

## Anglisismer

Unødvendige anglisismer — bruk norsk:

| Anglisisme | Norsk alternativ |
|----------|-----------------|
| «adressere et problem» | «løse», «fikse», «ta tak i» |
| «på slutten av dagen» | «til syvende og sist», eller dropp |
| «ta eierskap til» | «ha ansvar for» |
| «delivere» | «levere» |
| «har du noen input?» | «har du innspill?» |
| «deploye» | «rulle ut» |
| «shippe» | «levere», «sende ut» |
| «reviewe» | «gå gjennom», «se over» |
| «release» (som verb) | «gi ut», «rulle ut» |
| «tracke» | «følge med på», «spore» |
| «aligne» | «samkjøre», «enes om» |
| «triage» | «prioritere», «sortere» |
| «være på samme side» | «være enige» |
| «i henhold til» (overbrukt) | «etter», «ifølge» |
| «per dags dato» | «nå», «i dag» |

## Teksttyper

| Teksttype | Tone | Tips |
|-----------|------|------|
| ADR (`docs/adr/`) | Nøytral, teknisk | Kontekst → Beslutning → Konsekvenser. Beslutning i presens, aktiv form. |
| README | Direkte, vennlig | Start med hva tjenesten gjør, deretter oppsett (`./gradlew run`, NAIS). Ikke selg prosjektet. |
| Loggmelding | Entydig, søkbar | Konkret hendelse + nøkkelverdier. Ingen svulstige ord. Logg aldri personopplysninger (fnr, navn, helse). |
| Feilmelding / API-respons | Enkel, handlingsrettet | Hva gikk galt + hva mottakeren kan gjøre. Aktiv form. |
| PR-beskrivelse | Konkret | Hva endres, hvorfor. Lenk til issue/ADR. |
| Commit-melding | Konkret, imperativ | Følg conventional commits hvis repoet bruker det. |

## Feilmeldinger og API-respons

Backenden produserer tekst som lekker ut: HTTP-feilrespons til frontend, kafka-feil, og logger i Kibana. Skriv dem like presist som UI-tekst.

- Forklar problem + hva mottakeren gjør, i aktiv form.
- Skill mellom intern logg (for utvikler) og respons til klient (for sluttbruker/tjeneste).
- Logg aldri sensitive data — bruk identifikatorer (f.eks. `behandlingId`), ikke fnr eller helseopplysninger.

### Mini-mal for feilmelding

1. Hva gikk galt
2. Hva mottakeren kan gjøre nå
3. Eventuelt hvor det finnes mer hjelp

Eksempel (API-respons fra en Ktor-route):

```kotlin
// ❌ kryptisk og engelsk-blandet
call.respond(HttpStatusCode.BadRequest, "Invalid input.")

// ✅ presist, norsk, handlingsrettet
call.respond(
    HttpStatusCode.BadRequest,
    "Mangler fødselsdato. Send 'fodselsdato' på formatet ÅÅÅÅ-MM-DD.",
)
```

Eksempel (loggmelding):

```kotlin
// ❌ svulstig og uten kontekst
log.info("Successfully orchestrated the seamless processing of the event")

// ✅ entydig og søkbar, uten persondata
log.info("Behandlet melding fra topic {} med behandlingId {}", topic, behandlingId)
```

## Før og etter

Se `references/for-og-etter.md` for fyldige eksempler: feiloversatt fagterm, substantivsyke, stiv tone, PR-beskrivelse, README, API-respons og unødvendig oppsummering.

## AI-markører

Se `references/ai-markorer.md` for full oversikt over mønstre som avslører KI-generert norsk: svulstige ord, engelske AI-ord som siver inn, em-dash-mønster, falsk symmetri, og nynorsk/svensk-former som blander seg inn i bokmål.

## Grenser

### ✅ Alltid
- Behold etablerte engelske fagtermer (token, secret, namespace, consumer ...)
- Bindestrek i sammensatte ord med engelske termer
- Konsekvent formvalg gjennom hele teksten
- Hold persondata ute av logger og feilmeldinger

### ⚠️ Spør først
- Endringer som kan påvirke faglig eller teknisk innhold
- Omstrukturering av hele dokumenter (README, ADR)

### 🚫 Aldri
- Endre faglig innhold eller tekniske beslutninger
- Oversette etablerte engelske fagtermer til norsk
- Innføre nynorsk i bokmålstekster

## Kilder

- [Språkrådets klarspråk-prinsipper](https://sprakradet.no/Klarsprak/) og [KI-rapport](https://sprakradet.no/aktuelt/ki-sprakets-fallgruver/) (jan 2025)
- [ISO 24495-1](https://sprakradet.no/klarsprak/kunnskap-om-klarsprak/iso-standard-for-klarsprak/) — internasjonal klarspråk-standard
- [Digdirs klarspråk-veileder](https://www.digdir.no/klart-sprak/ny-veileder-om-klart-sprak-i-utvikling-av-digitale-tjenester/3603) — klarspråk i digitale tjenester
- [Termportalen](https://www.termportalen.no/) — norske faguttrykk (UiB/Språkrådet)
