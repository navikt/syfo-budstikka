# ADR 0012 — SendingWindowGate følger DecisionRule-mønsteret

- Status: foreslått
- Dato: 2026-07-30
- Relatert: ADR 0011, ADR 0013, ADR 0001 (domeneblind), issue #27, issue #166 (hold-plassering, avgjort i ADR 0014)

## Kontekst

Beslutningskjernen komponerer regler etter `DecisionRule`/`ResolvedRule`-mønsteret
(`resolve` → `ResolvedRule` → `apply`), slik `DeathGate` gjør. En sendevindu-regel
utenfor dette mønsteret ville gitt inkonsistent arkitektur og gjort den vanskelig
å sette sammen med de andre reglene i dispatch-livet.

## Beslutning

`SendingWindowGate` implementerer `DecisionRule` og følger `DeathGate`-mønsteret.

- Gaten self-selekterer: den slår kun til for hendelser merket
  `SendingWindow.BUDSTIKKA_OPENING_HOURS` (via `content.gatedSendingWindow()`).
  Andre hendelser slippes uendret gjennom.
- Er vinduet stengt (`BudstikkaSendingWindowLookup.isClosed(now)`), returnerer
  `apply` en `Decision.NotInSendingWindow(nextRetry, reason)`; ellers
  `Decision.Processed(deliveries)`.
- `nextRetry` beregnes som neste åpne tidspunkt (`nextOpen`), og `reason` hentes
  fra første bruddregel — nyttig for logg og observability.
- Klokka injiseres (`clock: Clock`) slik at gaten testes deterministisk.

Gaten uttrykker kun *beslutningen* «utenfor vinduet, prøv igjen ved `nextRetry`».
**Hvor** en ventende leveranse holdes fram til vinduet åpner (inbox vs. outbox)
er ikke avgjort her — det er åpent i issue #166 og påvirker ikke selve
gate-mønsteret.

## Alternativer vurdert

- **Beholde en primitiv regel utenfor `DecisionRule`:** arkitektur-drift fra
  resten av beslutningskjernen og vanskeligere komposisjon/testing.

## Konsekvenser

- Konsistent gate-mønster over alle dispatch-regler; enklere komposisjon.
- Gaten kan testes gjennom `DecisionRule`-grensesnittet med injisert klokke.
- Konsekvensen av `Decision.NotInSendingWindow` (persistering/retry) håndteres av
  skallet og avhenger av hold-plasseringen som avgjøres i #166.
