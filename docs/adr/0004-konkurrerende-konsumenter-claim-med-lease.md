# ADR 0004 — Konkurrerende workere bruker claim og lease

- Status: Besluttet, delvis implementert
- Dato: 2026-07-10
- Utestående retting: issue #157

Flere replikaer claimer ulike rader uten overlapp med `FOR UPDATE SKIP LOCKED` og en
tidsbegrenset lease. Claimet committes før eksterne oppslag, og terminale
tilstandsendringer er atomiske og tilstandsgarderte. Utløpt lease gjør raden
tilgjengelig igjen etter krasj.

Dette ble valgt fremfor å holde en databaselås over nettverks-I/O eller la flere
replikaer gjøre de samme oppslagene før en avsluttende konkurranse. En lease kan
føre til gjentatt behandling etter timeout, så effekter må være idempotente.
Retrybudsjettet skal telle varige autorisasjoner til faktisk radbehandling, ikke
claims av rader som aldri blir behandlet; denne semantikken rettes i #157.
