## Beskrivelse

<!-- Hva gjør denne PR-en og hvorfor? -->

## Endringer

<!-- - `fil/modul`: Hva som ble endret -->

## Issue

<!-- Closes #NUMMER / Relates to #NUMMER -->

## Verifikasjon

<!-- Lim inn ferskt bevis fra de deterministiske gatene (kommando + exit-kode). -->

```
./gradlew build   # exit: 0  (kompilering + ktlint + test)
```

## Sjekkliste

- [ ] `./gradlew build` grønt (kompilering + ktlint + test)
- [ ] Ingen sensitive data eksponert (tokens, credentials, fnr/PII) — heller ikke i logger
- [ ] R3/R4 etter repoets policy? → siste `grill-inspektor`-verdikt gjelder
      nåværende diff og er `APPROVED`; eventuelle `CONCERNS` er eksplisitt
      akseptert i issue/PR; eller et eksplisitt menneskelig unntak er dokumentert der
- [ ] Endrede API-/event-kontrakter koordinert med berørte team
- [ ] Dokumentert løp eksplisitt valgt for eventuelle nye glossar-/ADR-endringer;
      `/domain-modeling` har kvalifisert ADR-kandidater
