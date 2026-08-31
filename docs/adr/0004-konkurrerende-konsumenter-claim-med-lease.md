# ADR 0004 — Konkurrerende workere bruker claim og lease

- Status: Besluttet, implementert
- Dato: 2026-07-10

Flere replikaer claimer ulike rader uten overlapp med `FOR UPDATE SKIP LOCKED` og en
tidsbegrenset lease. Claimet committes før eksterne oppslag, og terminale
tilstandsendringer er atomiske og tilstandsgarderte. Utløpt lease gjør raden
tilgjengelig igjen etter krasj.

Dette ble valgt fremfor å holde en databaselås over nettverks-I/O eller la flere
replikaer gjøre de samme oppslagene før en avsluttende konkurranse. En lease kan
føre til gjentatt behandling etter timeout, så effekter må være idempotente.
En worker som taper den avsluttende CAS-en er derfor et forventet konkurranseutfall:
det telles i `inbox_message_decision_cas_lost_total`, men logges ikke per melding.
Retrybudsjettet teller varige autorisasjoner til faktisk radbehandling
(`beginAttempt` i samme gatede `UPDATE` som inkrementet, rett før første feilbare
arbeid), ikke claims: en claimet rad som aldri ble behandlet beholder budsjettet
sitt, og et sendevindu-hold leverer forsøket tilbake. Poison-gaten terminerer
derfor bare rader som faktisk har startet behandling `maxAttempts` ganger.
