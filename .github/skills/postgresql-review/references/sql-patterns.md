# SQL patterns — NAV-specific tuning

This reference covers only NAV-specific settings.

Generic SQL optimisation (EXPLAIN ANALYZE, index choice, N+1, SELECT *, JSONB operators, window functions, upsert/ON CONFLICT, advisory locks, range partitioning) is out of scope for this skill. See the PostgreSQL documentation or the team's established practice.

## Connection pool — HikariCP in NAIS containers

The NAV default is Cloud SQL via `gcp.sqlInstances` in the NAIS manifest. The pool size must be sized against `replicas.max` and Cloud SQL's `max_connections`, not JVM defaults.

```yaml
# NAIS — Cloud SQL instance (injects BUDSTIKKA_DB_URL, BUDSTIKKA_DB_USERNAME, BUDSTIKKA_DB_PASSWORD
# and friends as env — see nais/nais-dev.yaml and nais/nais-prod.yaml)
spec:
  replicas:
    min: 2
    max: 4
  gcp:
    sqlInstances:
      - type: POSTGRES_18
        databases:
          - name: budstikka-db
            envVarPrefix: BUDSTIKKA_DB
```

```kotlin
// In this repository the BUDSTIKKA_DB_* env vars flow through application.conf's database { }
// block, and infrastructure/database/config/Config.kt builds the jdbc url and the pool config.
// Size the pool from verified capacity:
HikariConfig().apply {
    jdbcUrl = databaseConfig.jdbcUrl        // "jdbc:" + BUDSTIKKA_DB_URL, credentials stripped
    username = databaseConfig.username      // BUDSTIKKA_DB_USERNAME
    password = databaseConfig.password      // BUDSTIKKA_DB_PASSWORD
    // Set pool, timeout and isolation values from verified capacity and SLOs
}
```

**Sizing:** `replicas.max × maximumPoolSize ≤ max_connections`. `max_connections` is set according to the Cloud SQL tier — shared-core is below 100, so run `SHOW max_connections;` before you do the maths, and remember that migration/admin connections and other apps on the same instance count towards it.

**Explicit `READ_COMMITTED`:** Matches the PostgreSQL default and avoids surprises on driver upgrades.

> **⚠️ Ask first** before `maximumPoolSize > 5` — it is nearly always a symptom of slow queries or missing indexes, not of a pool shortage.
