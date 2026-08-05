# ADR 0014 — Ventende opprettelser holdes på inbox

- Status: Besluttet, delvis implementert
- Dato: 2026-08-03
- Godkjent: 2026-08-05
- Utestående implementering: issue #26

En `OPPRETT` utenfor sendevinduet holdes som `WAIT` på inbox uten at en leveranse
materialiseres. En `FERDIGSTILL` som kommer før utsending, skal kansellere den
ventende opprettelsen direkte. Hvis opprettelsen allerede er levert, brukes den
vanlige ferdigstillingsflyten mot lagret leveranse.

Vi valgte inbox fremfor outbox fordi en opprettelse som lukkes før sending da
aldri materialiseres, og fordi modellen ikke trenger en egen kansellert
leveransetilstand. Kostnaden er to matchflater og et samtidighetskrav: enten
vinner kanselleringen og ingen leveranse opprettes, eller så lagres
opprettelsen først og ferdigstillingen lukker den etterpå.

`WAIT` og oppvåkning er implementert. Direkte kansellering, nødvendig indeks og
serialisering av racet gjenstår i #26.
