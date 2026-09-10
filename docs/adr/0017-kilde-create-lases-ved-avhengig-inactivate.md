# ADR 0017 — Kilde-`CREATE` låses ved avhengig `INACTIVATE`

- Status: Besluttet
- Dato: 2026-09-10

ADR 0004s lease-baserte claim-modell er fortsatt den generelle strategien. Denne beslutningen
avgrenser den for rekkefølgen mellom en kilde-`CREATE` og en avhengig `INACTIVATE`: En
PostgreSQL session advisory lock, nøkkelt på kilde-`CREATE`, holdes over ekstern utsending og den
lokale terminale tilstandsoppdateringen. Lease alene kan ikke gjerde ute en allerede kjørende,
utdatert avsender.

En FERDIGSTILL materialiserer én avhengig `INACTIVATE` for hver lagret `CREATE` som matcher
reference, channel og recipient. Hver avhengighet peker til den eksakte kilden og avleder payload
og eventuell ekstern ID fra den; en mislykket eller ugyldig kilde undertrykker ikke søsken.

Låsen tas ikke-blokkerende og binder én pooled connection under kallet. Ved usikker opplåsing
forkastes sesjonen. Tap av sesjon eller prosess frigjør låsen, men gir ikke en distribuert
transaksjon. Stabile nedstrøms-ID-er, idempotens og avstemming er derfor fortsatt nødvendige.

Blandede gamle og nye replikaer er utrygt fordi gammel claim- og dispatch-kode ignorerer den nye
avhengigheten og guarden. Rekkefølgesendringen deployes derfor først etter en eksplisitt barriere
der gamle replikaer og pågående gamle workere har stoppet, før FERDIGSTILL-produsenter aktiveres.
Rollback til worker-atferd før V10 er ikke trygt mens avhengige rader finnes; gammel retention
mangler også kildefilteret og kan feile på den nye foreign key-en.
