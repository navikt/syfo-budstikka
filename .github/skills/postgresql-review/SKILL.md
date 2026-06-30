---
name: postgresql-review
description: "Brukes når du setter opp eller endrer DataSource/connection-pool i no.nav.syfo-backend, ser connection-feil i loggen, velger databaseteknologi, eller reviewer en schema-/migrasjonsendring i Postgres. Dekker HikariCP-pool og dimensjonering mot Cloud SQL/replicas, samspill med Flyway og koordinering av delte schemas."
---

# PostgreSQL-gjennomgang

Gjennomgang av PostgreSQL-bruk i dette repoet. Dekker NAV-spesifikk tilkoblingspool-dimensjonering (NAIS-replicas + Cloud SQL), indekser, anti-mønstre, migrasjoner og koordinering av delte schemas.

Se [references/sql-patterns.md](references/sql-patterns.md) for NAV-spesifikt HikariCP- og `gcp.sqlInstances`-oppsett, og [references/migration-flyway.md](references/migration-flyway.md) for migrasjonsmønstre. Generisk SQL-tuning (indeksvalg, JSONB, window functions, upsert, partisjonering, N+1) er utenfor scope — det kan modellen selv, eller slå opp i PostgreSQL-dokumentasjonen.

## Database-valgtre i NAV-kontekst

NAV-default er PostgreSQL via `gcp.sqlInstances` i NAIS-manifestet. Velg teknologi før du skriver kode:

| Behov | Valg | Begrunnelse |
|-------|------|-------------|
| Transaksjonell tilstand, CRUD, saksbehandling | **PostgreSQL** (`gcp.sqlInstances`) | NAV-default. ACID, Flyway-migrasjoner, godt støttet i plattformen. |
| Kun cache eller efemer tilstand | **Ingen DB** (Valkey eller in-memory) | Unngå Cloud SQL-kostnad og drift hvis data kan gjenskapes. |
| Analyse, rapportering, store aggregeringer | **BigQuery** (dataplattform) | For dataplattform/analytics — ikke for operasjonell drift. |
| Hendelsesflyt mellom tjenester | **Kafka**, ikke DB | Rapids & Rivers / domene-events. DB er ikke integrasjonsmekanisme. |

> **⚠️ Spør først** før du introduserer ny database-type i en tjeneste som ikke har det fra før — det påvirker drift, backup og tilgangsstyring.

## HikariCP for NAIS-containere

Pool-størrelsen må tilpasses NAIS-replicas og Cloud SQL-grensene, ikke JVM-defaults. Sett opp `DataSource` eksplisitt — ikke len deg på Hikari sine defaults (`maximumPoolSize = 10`), som er farlig for en tjeneste som kan skaleres ut.

```kotlin
// Konfigurer fra env-variabler injisert av gcp.sqlInstances (DB_JDBC_URL, DB_USERNAME, DB_PASSWORD)
fun dataSource(env: ApplicationEnvironment): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = env.config.property("db.jdbcUrl").getString()
        username = env.config.property("db.username").getString()
        password = env.config.property("db.password").getString()
        maximumPoolSize = 3                          // Start smått — 3–5 for typiske NAV-tjenester
        minimumIdle = 1
        connectionTimeout = 10_000                   // 10s — feil raskt hvis Cloud SQL Proxy er nede
        idleTimeout = 300_000                        // 5 min — slipp idle connections raskt
        maxLifetime = 1_800_000                      // 30 min — under Cloud SQL sin restart-terskel
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
    })
```

**Dimensjoneringsformel:**

```
replicas.max × maximumPoolSize ≤ max_connections
```

Cloud SQL setter `max_connections` etter instansens tier/minne — mindre tiers (shared-core som `db-f1-micro`) ligger vesentlig under 100, så verifiser med `SHOW max_connections;` før du regner (dev og prod kjører ofte ulik tier). Med `replicas.max = 4` og `maximumPoolSize = 3` bruker du maks 12 — trygt selv på en liten tier. Med `replicas.max = 10` og `maximumPoolSize = 20` bruker du 200 — tjenesten faller over så snart det overstiger `max_connections`.

**Begrunnelse for `maxLifetime = 30 min`:** Cloud SQL Proxy kan restarte (vedlikehold, node-bytter). Med lavere lifetime byttes connections ut før proxyen tvinger brudd, så du unngår "connection reset"-feil i applikasjonsloggen.

**Begrunnelse for `transactionIsolation`:** Eksplisitt `READ_COMMITTED` matcher PostgreSQL-default og unngår overraskelser når driver-defaults endres mellom versjoner.

> **⚠️ Spør først** før du øker `maximumPoolSize` over 5 — det er nesten alltid symptom på langsomme spørringer eller manglende indekser, ikke pool-mangel.

Se [references/sql-patterns.md](references/sql-patterns.md) for fullstendig HikariCP- og `gcp.sqlInstances`-oppsett.

## Delt database — koordinering av migrasjoner

Flere NAV-team leser ofte fra samme Cloud SQL-instans (felles domene-data). Schema-endringer er da ikke lokale.

**Betinget råd:** Hvis andre team leser fra din database, koordiner schema-endringer med konsument-team FØR merge.

Sjekk før destruktive migrasjoner:

- [ ] Er det andre apper/team med tilgang til denne `gcp.sqlInstances`-instansen?
- [ ] Sjekket du `DROP COLUMN`, `ALTER COLUMN TYPE`, `RENAME` mot konsumentenes spørringer?
- [ ] Har konsumentene deployet kode som tåler det nye skjemaet før du merger?

Bruk **trestegs feltmigrasjon** (expand-migrate-contract) for delte schemas:

1. **Expand:** Legg til ny kolonne/tabell. Ingen konsumenter påvirkes.
2. **Migrate:** Dual-write fra produsent, konsumenter migrerer lesing til ny kolonne én om gangen.
3. **Contract:** Fjern gammel kolonne i separat PR når alle konsumenter er bekreftet migrert (sjekk produksjonstrafikk i 2+ uker).

> **🚫 Aldri** kjør `DROP COLUMN` eller `ALTER COLUMN TYPE` på delt schema uten forhåndsvarsel og bekreftelse fra konsument-team. Én deploy kan ta ned andres tjenester.

Selve migreringsfilene følger `flyway-migration`-skillen i dette repoet. Se også [references/migration-flyway.md](references/migration-flyway.md).

## Generisk SQL-tuning

Indeksstrategier, JSONB-mønstre, upsert/ON CONFLICT, CHECK/UNIQUE-constraints, advisory locks, partisjonering og anti-mønstre (N+1, SELECT *, manglende LIMIT) er generisk PostgreSQL-kunnskap. Bruk de standard NAV-prinsippene:

- Indekser på FK-kolonner og hyppige WHERE-kolonner
- `@>` + GIN-indeks for JSONB-containment, `->>` for nøkkeloppslag
- `ON CONFLICT` kun mot faktisk `UNIQUE`-constraint
- Batch-henting (`findByIdIn`) i stedet for N+1
- LIMIT på spørringer som kan returnere mange rader
- `CREATE INDEX CONCURRENTLY` i egen migrering utenfor transaksjon — se `flyway-migration`-skillen

Partisjonering og advisory locks: **⚠️ Spør først** før du introduserer dem i en eksisterende løsning.

## Migrasjoner

For Flyway-migrasjoner og SQL-konvensjoner, følg `flyway-migration`-skillen i dette repoet. Nøkkelpunkter:

- Bruk `TIMESTAMPTZ` (ikke `TIMESTAMP`) for alle tidsstempel-kolonner
- Indekser på alle FK-kolonner
- UUID-primærnøkler med `gen_random_uuid()`
- Egne migreringer for `CREATE INDEX CONCURRENTLY` (kan ikke kjøre i transaksjon)
- Repeterbare migreringer (`R__*.sql`) for views, funksjoner og triggers
- Koordiner destruktive endringer med konsument-team for delte schemas

Se [references/migration-flyway.md](references/migration-flyway.md) for konkrete eksempler.

## Sjekkliste

- [ ] HikariCP: `maximumPoolSize` 3–5, `maxLifetime = 30 min`, `transactionIsolation` satt eksplisitt
- [ ] Dimensjonering: `replicas.max × maximumPoolSize ≤ max_connections`
- [ ] Database-valg bekreftet (PostgreSQL for operasjonell, BigQuery for analyse, Kafka for integrasjon)
- [ ] Delt schema? Konsument-team varslet før destruktive endringer
- [ ] Indekser på alle FK-kolonner og hyppig brukte WHERE-kolonner
- [ ] `CREATE INDEX CONCURRENTLY` vurdert for nye prod-indekser på store tabeller
- [ ] CHECK/UNIQUE constraints brukt der domeneregler kan håndheves i databasen
- [ ] Ingen N+1-spørringer
- [ ] SELECT bare kolonner som trengs
- [ ] LIMIT på spørringer som kan returnere mange rader
- [ ] Revert-vei vurdert (forward-only: ny `V{n+1}`-migrering, ikke undo — se `/flyway-migration`)
- [ ] Ingen `SELECT *` i produksjonskode

## Grenser

### ✅ Alltid
- HikariCP `maximumPoolSize` 3–5, `maxLifetime = 30 min` for Cloud SQL
- Verifiser `replicas × pool ≤ max_connections` før prod-deploy
- Indekser på FK-kolonner og hyppige WHERE-kolonner
- `TIMESTAMPTZ` for alle tidsstempel-kolonner
- LIMIT på spørringer som kan returnere mange rader
- Varsle konsument-team ved schema-endringer på delt database

### ⚠️ Spør først
- `maximumPoolSize > 5` (nesten alltid symptom, ikke løsning)
- Ny database-type (BigQuery, Valkey) i tjeneste som ikke har det
- Nye indekser på store tabeller i produksjon — bruk `CONCURRENTLY` ved behov
- Partisjonering eller advisory locks i eksisterende løsninger
- Destruktive migrasjoner (`DROP COLUMN`, `ALTER TYPE`, `RENAME`) på delte schemas

### 🚫 Aldri
- `SELECT *` i produksjonskode
- N+1-spørringer
- `DROP TABLE` i produksjon uten backup-plan
- `TIMESTAMP` uten tidssone (bruk `TIMESTAMPTZ`)
- `DROP COLUMN` på delt schema uten bekreftet konsument-migrasjon
- Bruk database som integrasjonsmekanisme mellom team (bruk Kafka/API)

## Kobling til faseløkken

Når en database-beslutning inngår i en planlagt endring, noter valgene i `docs/CONTEXT.md` (databaseteknologi, pool-dimensjonering, delt vs. eget schema, konsumenter) og fang varige arkitekturvalg som ADR under `docs/adr/` — f.eks. PostgreSQL vs. Kafka for et integrasjonsbehov, eller expand-migrate-contract-strategi for et delt schema. Speil pool- og migrasjonssteg i `.grill/PLAN.md`. Verifiser pool-dimensjonering og at migrasjoner kjører grønt (Testcontainers) og legg evidensen i `.grill/VERIFICATION.md` før PR. For endringer som rører delte schemas eller pool-konfig er det verdt en ekstra review (`grill-inspektor`) før merge.

## Referansefiler

| Fil | Innhold |
|-----|---------|
| [references/sql-patterns.md](references/sql-patterns.md) | NAV-spesifikk HikariCP-tuning og `gcp.sqlInstances`-oppsett |
| [references/migration-flyway.md](references/migration-flyway.md) | Migrasjonsmønstre: TIMESTAMPTZ, FK-indekser, UUID, CONCURRENTLY, repeterbare migreringer, trestegs feltmigrasjon |
