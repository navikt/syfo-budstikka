---
name: grill-me
description: "Bruk når du vil ha en rask, nådeløs stresstest av en plan eller idé UTEN den fulle design-/dokumentasjonsflyten. Si 'grill meg'. For full design med ADR/glossar, bruk /grill-with-docs i stedet."
---

# grill-me

Lettvekts grilling: et nådeløst intervju som stresstester en plan eller idé. Ingen påkrevde artefakter — målet er å avdekke svake antakelser og uavklarte valg, raskt.

## Kontrakt
1. **Ett spørsmål av gangen.** Aldri flere samtidig.
2. Hvert spørsmål kommer med **din anbefalte svar** + kort begrunnelse.
3. Gå ned hvert gren av beslutningstreet; løs avhengige valg ett for ett.
4. Kan svaret finnes i kodebasen → **utforsk i stedet for å spørre**.
5. Grav dypere når et svar avdekker usikkerhet.
6. Fokuser på: feil antakelser, manglende kanttilfeller, skjulte avhengigheter, over-engineering, enklere alternativer.

## Når oppgradere til full flyt
Avdekker grillen at dette er en reell arkitektur-, datamodell- eller kontraktsbeslutning → foreslå `/grill-with-docs`, som fanger beslutninger som ADR + glossar i `.grill/`. For en ren plan-stresstest holder denne.
