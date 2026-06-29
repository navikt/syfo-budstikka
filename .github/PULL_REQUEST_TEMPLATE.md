## Beskrivelse

<!-- Hva gjør denne PR-en og hvorfor? -->

## Endringer

<!-- - `fil/modul`: Hva som ble endret -->

## Issue

<!-- Closes #NUMMER / Relates to #NUMMER -->

## Verifikasjon

<!-- Lim inn ferskt bevis fra de deterministiske gatene (kommando + exit-kode), f.eks. fra .grill/VERIFICATION.md -->

```
./gradlew build   # exit: 0  (kompilering + ktlint + test)
```

## Sjekkliste

- [ ] `./gradlew build` grønt (kompilering + ktlint + test)
- [ ] Ingen sensitive data eksponert (tokens, credentials, fnr/PII) — heller ikke i logger
- [ ] Høyrisiko (auth, PII, schema/Flyway, Kafka, API-kontrakt, NAIS `accessPolicy`, deploy)? → kryssmodell-review kjørt, verdikt ikke-😞 (se `.grill/REVIEW.md`)
- [ ] Endrede API-/event-kontrakter koordinert med berørte team
- [ ] ADR skrevet for vanskelig-å-reversere beslutninger (`docs/adr/`)
