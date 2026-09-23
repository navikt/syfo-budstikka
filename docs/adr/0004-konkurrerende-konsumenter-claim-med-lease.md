# ADR 0004 — Konkurrerende workere bruker claim og lease

- Status: Besluttet, implementert
- Dato: 2026-07-10
- Oppdatert: 2026-09-23

Flere replikaer claimer ulike rader uten overlapp med `FOR UPDATE SKIP LOCKED` og en
tidsbegrenset lease. Claimet committes før eksterne oppslag, og terminale
tilstandsendringer er atomiske. Hver claim skriver en ny `claim_token`, og
`beginAttempt`, terminale overganger og overgang til WAIT bruker compare-and-set
på `CLAIMED` og `claim_token`. En worker som fullfører etter at leasen er utløpt,
kan dermed ikke overskrive en nyere claim. Utløpt lease gjør raden tilgjengelig
igjen etter krasj. Kolonnen er nullable for rullerende utrulling. Gamle podder
verken skriver eller sjekker tokenet, så fencingen virker fullt først når alle
podder kjører ny kode. En tilstandsavhengig CHECK-constraint krever derfor at
utrullingen er ferdig og at gjenværende token på rader utenfor `CLAIMED` er
nullstilt først.

Dette ble valgt fremfor å holde en databaselås over nettverks-I/O eller la flere
replikaer gjøre de samme oppslagene før en avsluttende konkurranse. En lease kan
føre til gjentatt behandling etter timeout. Delivery sender før sin terminale
CAS og er fortsatt at-least-once; effekter må være idempotente. En tapt
delivery-claim telles i `delivery_claim_lost_total` og logges. Inbox teller tapte
beslutnings-CAS-er i `inbox_message_decision_cas_lost_total` uten logging per melding.
Retrybudsjettet teller varige autorisasjoner til faktisk radbehandling
(`beginAttempt` i samme gatede `UPDATE` som inkrementet, rett før første feilbare
arbeid), ikke claims: en claimet rad som aldri ble behandlet beholder budsjettet
sitt, og et sendevindu-hold leverer forsøket tilbake. Poison-gaten terminerer
derfor bare rader som faktisk har startet behandling `maxAttempts` ganger.
