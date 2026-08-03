# ADR 0011 — OpeningHours som gjenbrukbar åpningstids-komposisjon

- Status: foreslått
- Dato: 2026-07-30
- Relatert: ADR 0012, ADR 0013, ADR 0001 (domeneblind), issue #27, issue #171

## Kontekst

Dispatch skal kunne gates til et sendevindu (issue #27). Åpningstidslogikk —
ukedager, klokkeslett og norske røde dager — trengs av `SendingWindowGate` og
kan gjenbrukes av andre regler senere. Legges logikken direkte inn i én gate,
blir den ugjenbrukbar og vanskelig å teste isolert, og hver ny regel må kjenne
gate-konteksten for å teste den.

## Beslutning

Åpningstider modelleres som en gjenbrukbar komposisjon, ikke et enkeltobjekt med
fast konfigurasjon.

- En generisk `openingHours { }`-builder i `domain/foundation/calendar/` setter
  sammen uavhengige `OpeningRule`-er: `ClosedOnDays` (ukedager), `ClosedOnDates`
  (kalenderdatoer, default norske røde dager via `NorwegianRodeDager`) og
  `OpenBetween` (klokkeslettvindu innen samme døgn).
- `OpeningHours` eksponerer `isOpen`, `opensAt` (neste åpne tidspunkt innen en
  horisont) og `violations` (hvilke regler som stenger). Ingen antakelse om
  budstikkas konkrete tider ligger i selve klassen.
- Den konkrete budstikka-konfigurasjonen (09–20 man–lør, søndag stengt, røde
  dager, julaften) bor i `BudstikkaSendingWindowLookup`, ikke i `OpeningHours`.
  Se ADR 0013 for hvilke dager som stenges.
- Tidssone er `Europe/Oslo`.

## Alternativer vurdert

- **Logikken inne i `SendingWindowGate`:** enklere lokalt, men ugjenbrukbar og
  tester må kjenne gate-konteksten.
- **Konfigurerbar via miljøvariabel:** unødvendig kompleksitet for sjelden
  endrede åpningstider; endring via deploy er akseptabelt.

## Konsekvenser

- Åpningstidslogikk kan testes isolert (regel for regel og samlet), uten gate.
- Andre gates/regler kan gjenbruke `openingHours { }`-komposisjonen.
- Endring av åpningstider krever kodeendring i `BudstikkaSendingWindowLookup` og
  deploy (akseptabelt).
