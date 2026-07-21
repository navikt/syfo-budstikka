---
name: klarsprak
description: Brukes når norsk tekst i syfo-budstikka skal skrives eller vaskes — feilmeldinger og API-respons, loggmeldinger, ADR-er i docs/adr/, README, PR-beskrivelser, commit-meldinger og release-notes — eller når brukeren ber om klarspråk, språkvask, fjerning av AI-markører eller retting av anglisismer. Også /klarsprak.
---
# Klarspråk — norsk teknisk skriving

Vask norsk prosa som lekker ut av backenden (API-feilrespons, Kibana-logg) eller lever i repoet (ADR, README, PR, commit) så den blir like presis som koden. Organisasjonen skrives **Nav**, ikke «NAV» (unntak: `NAIS`, `NAVident`, `no.nav.syfo`).

Grunnreglene for norsk markdown (AI-markører, det viktigste først, substantivsyke, Nav/NAV) bor i `.github/instructions/norwegian-text.instructions.md` — én sannhetskilde. Denne skillen legger til vaske-prosessen og backend-teksttypene.

## Vask — steg

1. **Konklusjonen først.** Beslutningen (ADR) eller hva endringen gjør for konsumenten (PR) i første setning. Ferdig når leseren ser utfallet uten å lese videre.
2. **Verb framfor substantivsyke**, aktiv form («foretar en vurdering» → «vurderer»). Ferdig når ingen «foretar/gjennomfører en \<substantiv\>» står igjen.
3. **Rett unødvendige anglisismer** (full tabell i referanse). Behold etablerte fagtermer; bindestrek i sammensetninger (`Kafka-topicet`, `token-validering`).
4. **Fjern AI-markører**: svulstige adjektiv, em-dash-flom, falsk «ikke bare X, men Y»-symmetri, tomt oppsummeringspåheng. Full katalog: `references/ai-markorer.md`.
5. **PII-sjekk** for feilmelding/logg: ingen fnr, navn eller helseopplysninger — bruk tekniske id-er (`behandlingId`).

## Backend-teksttyper

Tekst herfra lekker til frontend, andre tjenester og Kibana — skriv den like presist som UI-tekst.

**Feilmelding / API-respons** — hva gikk galt + hva mottakeren gjør, aktiv form:
```kotlin
// ❌ "Invalid input."
// ✅
call.respond(HttpStatusCode.BadRequest, "Mangler fødselsdato. Send 'fodselsdato' på formatet ÅÅÅÅ-MM-DD.")
```
**Loggmelding** — konkret hendelse + nøkkelverdier, søkbar, aldri PII:
```kotlin
// ❌ log.info("Successfully orchestrated the seamless processing of the event")
// ✅ log.info("Behandlet melding fra topic {} med behandlingId {}", topic, behandlingId)
```
**ADR / PR / commit** — konklusjon først (steg 1); commit følger conventional commits.

## Grenser

- ✅ Behold etablerte engelske fagtermer (token, secret, namespace, consumer, endpoint); bindestrek i sammensetninger; konsekvent formvalg; PII ute av logg og feilmelding.
- ⚠️ Spør først ved omstrukturering av et helt dokument (README/ADR) eller endringer som rører faglig/teknisk innhold.
- 🚫 Aldri oversett etablerte fagtermer, innfør nynorsk i bokmål, eller endre tekniske beslutninger.

## Dypere referanse

- `references/fagtermer-og-anglisismer.md` — hva som holdes engelsk vs. norsk, full anglisisme- og teksttype-tabell, kode vs. prosa, kilder.
- `references/for-og-etter.md` — fyldige før/etter-eksempler (feiloversatt fagterm, substantivsyke, stiv tone, README, API-respons).
- `references/ai-markorer.md` — full katalog over KI-markører i norsk bokmål.
