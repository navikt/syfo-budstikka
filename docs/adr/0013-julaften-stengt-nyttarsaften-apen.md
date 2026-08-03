# ADR 0013 — Budstikka stenger julaften, men ikke nyttårsaften

- Status: foreslått
- Dato: 2026-07-30
- Relatert: ADR 0011, ADR 0012, issue #27, issue #171

## Kontekst

`NorwegianRodeDager` beregner Norges offisielle røde dager (faste datoer +
bevegelige avledet av påskedag etter Meeus/Jones/Butcher). Julaften (24.12) og
nyttårsaften (31.12) er **ikke** offisielle røde dager i Norge — de er halve
dager ved sedvane — og ligger derfor korrekt ikke i `NorwegianRodeDager`.

Budstikkas sendevindu må likevel ta stilling til disse to dagene. Issue #171
spesifiserer at budstikka skal være stengt julaften, men lister ikke
nyttårsaften.

## Beslutning

Budstikka stenger **julaften (24.12)**, men holder **nyttårsaften (31.12) åpen**.

- Julaften legges til som en egen `closedOn(Month.DECEMBER, 24, "Julaften")`-regel
  i `BudstikkaSendingWindowLookup` — altså i budstikkas konfigurasjon, ikke i den
  offisielle rødagslista i `NorwegianRodeDager`.
- Nyttårsaften får ingen slik regel og behandles som en vanlig virkedag innenfor
  09–20-vinduet (med mindre den faller på en søndag).

Dette er et budstikka-spesifikt driftsvalg, ikke en påstand om den nasjonale
kalenderen. `NorwegianRodeDager` forblir en ren kilde til offisielle røde dager.

## Alternativer vurdert

- **Også stenge nyttårsaften:** ikke i tråd med issue #171, og gir unødvendig
  tapt sendevindu 31.12.
- **Legge julaften inn i `NorwegianRodeDager`:** ville feilaktig framstille 24.12
  som en offisiell rød dag og forurenset en gjenbrukbar kalenderkilde med
  budstikka-spesifikk policy.

## Konsekvenser

- `BudstikkaSendingWindowLookup` returnerer `Closed` for 24.12 og `Open` (i
  vinduet) for 31.12.
- `NorwegianRodeDager` er trygg å gjenbruke som offisiell kalender uten
  budstikka-avvik.
