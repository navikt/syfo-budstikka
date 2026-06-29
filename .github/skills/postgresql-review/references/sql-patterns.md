# SQL-mønstre — NAV-spesifikk tuning

Denne referansen dekker kun NAV-spesifikke innstillinger. Se [SKILL.md](../SKILL.md) for prinsipper og sjekkliste.

Generisk SQL-optimalisering (EXPLAIN ANALYZE, indeksvalg, N+1, SELECT *, JSONB-operatorer, window functions, upsert/ON CONFLICT, advisory locks, range partitioning) er utenfor scope for denne skillen — modellen kan dette selv. Se PostgreSQL-dokumentasjonen, eller teamets egen best-practice hvis det finnes.

## Tilkoblingspool — HikariCP i NAIS-containere

NAV-default er Cloud SQL via `gcp.sqlInstances` i NAIS-manifestet. Pool-størrelsen må dimensjoneres etter `replicas.max` og Cloud SQL sin `max_connections`, ikke JVM-defaults.

```yaml
# NAIS — Cloud SQL-instans (injiserer DB_JDBC_URL, DB_USERNAME, DB_PASSWORD som env)
spec:
  replicas:
    min: 2
    max: 4
  gcp:
    sqlInstances:
      - type: POSTGRES_15
        databases:
          - name: budstikka-db
            envVarPrefix: DB
```

```kotlin
// HikariCP-defaults for et NAV Ktor-backend (no.nav.syfo)
HikariConfig().apply {
    jdbcUrl = System.getenv("DB_JDBC_URL")
    username = System.getenv("DB_USERNAME")
    password = System.getenv("DB_PASSWORD")
    maximumPoolSize = 3          // Start smått — 3–5 for typiske NAV-tjenester
    minimumIdle = 1
    connectionTimeout = 10_000   // Feil raskt hvis Cloud SQL Proxy er nede
    idleTimeout = 300_000        // 5 min — slipp idle connections raskt
    maxLifetime = 1_800_000      // 30 min — under Cloud SQL sin restart-terskel
    transactionIsolation = "TRANSACTION_READ_COMMITTED"
}
```

**Dimensjoneringsformel:** `replicas.max × maximumPoolSize ≤ max_connections`

Cloud SQL har `max_connections = 100` som default. Overvåk bruken ved skalering — husk at også migrerings-/admin-connections og andre apper på samme instans teller med.

**`maxLifetime = 30 min`:** Cloud SQL Proxy restartes ved vedlikehold/node-bytter. Lavere lifetime bytter ut connections før proxyen tvinger brudd, slik at du unngår "connection reset"-feil i loggen.

**Eksplisitt `READ_COMMITTED`:** Matcher PostgreSQL-default og unngår overraskelser ved driver-oppgraderinger.

> **⚠️ Spør først** før `maximumPoolSize > 5` — det er nesten alltid symptom på langsomme spørringer eller manglende indekser, ikke pool-mangel.
