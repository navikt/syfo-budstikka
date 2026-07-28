# SQL patterns — Nav-specific tuning

This reference covers only Nav-specific settings. Read the
[review catalog](review-catalog.md) for example values and the checklist.

Generic SQL optimisation (`EXPLAIN ANALYZE`, index selection, N+1, `SELECT *`,
JSONB operators, window functions, upsert/`ON CONFLICT`, advisory locks, and
range partitioning) is outside this skill's scope. Use PostgreSQL documentation
or team-specific guidance when available.

## Connection pool — HikariCP in NAIS containers

The Nav default is Cloud SQL through `gcp.sqlInstances` in the NAIS manifest.
Size the pool from `replicas.max` and Cloud SQL `max_connections`, not JVM defaults.

```yaml
# NAIS — Cloud SQL instance (injects DB_JDBC_URL, DB_USERNAME, DB_PASSWORD as env)
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
// Pool values are documented in the review catalog; this shows ENV WIRING:
HikariConfig().apply {
    jdbcUrl  = System.getenv("DB_JDBC_URL")   // injected by gcp.sqlInstances envVarPrefix: DB
    username = System.getenv("DB_USERNAME")
    password = System.getenv("DB_PASSWORD")
    // maximumPoolSize / minimumIdle / connectionTimeout / idleTimeout / maxLifetime
    // / transactionIsolation — see the review catalog for values and rationale
}
```

**Sizing:** `replicas.max × maximumPoolSize ≤ max_connections` (full explanation
in the [review catalog](review-catalog.md)). `max_connections` is set by Cloud
SQL tier; shared-core is below 100, so run `SHOW max_connections;` before the
calculation, and include migration/admin connections and other applications on
the same instance.

**Explicit `READ_COMMITTED`:** Matches the PostgreSQL default and avoids
surprises during driver upgrades.

> **⚠️ Ask first** before `maximumPoolSize > 5` — it is almost always a symptom
> of slow queries or missing indexes, not a lack of pool capacity.
