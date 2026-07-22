---
name: klarsprak
description: Brukes når norsk tekst i syfo-budstikka skal skrives eller vaskes — feilmeldinger og API-respons, loggmeldinger, ADR-er i docs/adr/, README, PR-beskrivelser, commit-meldinger og release-notes — eller når brukeren ber om klarspråk, språkvask, fjerning av AI-markører eller retting av anglisismer. Også /klarsprak.
---
# Klarspråk

Bruk denne skillen når norsk tekst skal strammes inn i kode, docs, PR, commit og logger.
Skriv **Nav** i løpende tekst (unntak: `NAIS`, `NAVident`, `no.nav.syfo`).

Grunnreglene bor i `.github/instructions/norwegian-text.instructions.md`. Denne skillen
er en kort operativ sjekkliste.

## Regler (kortversjon)

1. Start med utfallet i første setning.
2. Hold dokumentasjon så kort som mulig. Kutt detaljer som ikke påvirker beslutningen eller handlingen.
3. Skriv korte, tydelige og konsise setninger. Hold ett poeng per setning.
4. Bruk aktiv form.
5. Unngå duplisering. Si ting én gang, og kutt gjentakelser mellom avsnitt.
6. Behold tekniske ord på engelsk. Ikke oversett etablerte termer som `happy path`, `use case`, `dependency injection`, `override`, `token`, `consumer`, `endpoint`.
7. Bruk norsk kun for domeneord.
8. Bruk bindestrek i sammensatte ord med engelsk fagterm.
9. Fjern AI-markører (svulstige adjektiv, em-dash-flom, «ikke bare X, men Y»).
10. Ingen PII i logg/feilmelding; bruk tekniske ID-er.

## Korte før/etter-eksempler

```text
❌ Per-melding atomisk er en hard invariant.
✅ Hver melding behandles atomisk.
```

```text
❌ Vi foretar en vurdering av endringen.
✅ Vi vurderer endringen.
```

```text
❌ log.info("Behandlet fnr {}", fnr)
✅ log.info("Behandlet melding {}", meldingId)
```

## Grenser

- Be om avklaring før du omstrukturerer hele README/ADR-er.
- Ikke endre tekniske beslutninger mens du språkvasker.

## Referanser (ved behov)

- `references/fagtermer-og-anglisismer.md` — hva som bør være norsk vs. engelsk.
- `references/for-og-etter.md` — konkrete omskrivings-eksempler.
- `references/ai-markorer.md` — typiske AI-markører i norsk tekst.
