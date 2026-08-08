---
name: klarsprak
description: "Use when Norwegian user-facing prose in this repository is written or cleaned up — error messages shown to a user, API response text, README, PR descriptions, commit messages, release notes — or when existing ADR prose needs a language pass without changing the decision or its form. Triggers: 'plain language' / 'klarspråk', 'clean up this Norwegian text' / 'språkvask denne teksten', 'remove the AI markers' / 'fjern AI-markørene', anglicisms in Norwegian prose. Not for technical log messages: this repository logs in English."
---
# Klarspråk

Bruk denne skillen når **brukervendt norsk tekst** skal strammes inn: feilmeldinger
brukeren ser, API-respons, README, docs, PR-beskrivelser og commit-meldinger.
Tekniske loggmeldinger er utenfor: dette repoet logger på engelsk
(`logger.info("Delivery sent successfully")`) — ikke oversett dem.
Skriv **Nav** i løpende tekst (unntak: `NAIS`, `NAVident`, `no.nav.syfo`).
For ADR-er eier `docs/agents/domain.md` den lokale formen, mens
`/domain-modeling` bruker ADR-gaten og oppretter dokumentet etter valgt løp.
Denne skillen språkvasker bare eksisterende prosa.

Grunnreglene bor i `.github/instructions/norwegian-text.instructions.md`. Den lastes
ikke lenger automatisk for annet enn `README.md` — **les den med view-verktøyet før du
vasker norsk tekst utenfor README**. Denne skillen er en kort operativ sjekkliste.

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
10. Ingen PII i brukervendt feiltekst; vis tekniske ID-er i stedet. (Gulvet for
    logg og feil eier `.github/instructions/security.instructions.md`.)

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
❌ «Forespørselen kunne ikke gjennomføres grunnet manglende obligatoriske
   feltverdier for bruker <fnr>.»
✅ «Mangler mottaker. Oppgi «mottakerId» i forespørselen.»
```

## Grenser

- Be om avklaring før du omstrukturerer hele README/ADR-er.
- Ikke endre tekniske beslutninger mens du språkvasker.

## Referanser (ved behov)

- `references/fagtermer-og-anglisismer.md` — hva som bør være norsk vs. engelsk.
- `references/for-og-etter.md` — konkrete omskrivings-eksempler.
- `references/ai-markorer.md` — typiske AI-markører i norsk tekst.
