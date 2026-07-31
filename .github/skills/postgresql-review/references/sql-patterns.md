# SQL-mønstre — NAV-spesifikk tuning

Denne referansen dekker kun NAV-spesifikke innstillinger.

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
// Eksemplet viser ENV-wiringen; dimensjoner poolen fra verifisert kapasitet:
HikariConfig().apply {
    setJdbcUrl(System.getenv("DB_JDBC_URL"))   // injisert av gcp.sqlInstances envVarPrefix: DB
    setUsername(System.getenv("DB_USERNAME"))
    setPassword(System.getenv("DB_PASSWORD"))
    // Sett pool-, timeout- og isolation-verdier fra verifisert kapasitet og SLO-er
}
```

**Dimensjonering:** `replicas.max × maximumPoolSize ≤ max_connections`. `max_connections` settes etter Cloud SQL-tier — shared-core ligger under 100, så kjør `SHOW max_connections;` før du regner, og husk at migrerings-/admin-connections og andre apper på samme instans teller med.

**Eksplisitt `READ_COMMITTED`:** Matcher PostgreSQL-default og unngår overraskelser ved driver-oppgraderinger.

> **⚠️ Spør først** før `maximumPoolSize > 5` — det er nesten alltid symptom på langsomme spørringer eller manglende indekser, ikke pool-mangel.
