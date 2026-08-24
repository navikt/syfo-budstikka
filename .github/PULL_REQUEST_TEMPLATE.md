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
- [ ] R3/R4 etter repoets policy? → uavhengig gjennomgang gjelder nåværende
      diff; eventuelle bekymringer eller menneskelig unntak er dokumentert og
      akseptert i issue/PR
- [ ] Endrede API-/event-kontrakter koordinert med berørte team
- [ ] Eventuelle ADR-kandidater er vurdert etter `docs/agents/domain.md`
