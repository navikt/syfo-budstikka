# ADR 0015 — Ledervarsel har lukket oppgavetype

- Status: Besluttet
- Dato: 2026-07-17

`LedervarselCreate` bruker en lukket, budstikka-eid `Oppgavetype` med eksplisitt
mapping til Dine Sykmeldtes wire-verdi. Nye verdier legges til når en produsent
kobles til; budstikka skal bære verdien videre, men ikke forgrene oppførsel på
den.

Vi valgte dette fremfor en fri streng fordi produsentene ellers måtte kjenne
Dine Sykmeldtes kontrakt i tillegg til budstikkas. Valget gir typesikkerhet og
isolerer wire-endringer, men setter budstikka i release-løpet når en ny
oppgavetype tas i bruk.
