# PostgreSQL review catalog

Read this file for database selection, a concrete HikariCP example, SQL/Flyway
details, and the full review checklist. The main skill owns workflow and hard
boundaries.

## Database decision tree in Nav context

The Nav default is PostgreSQL through `gcp.sqlInstances` in the NAIS manifest.
Choose technology before writing code:

| Need | Choice | Reason |
|-------|------|-------------|
| Transactional state, CRUD, case processing | **PostgreSQL** (`gcp.sqlInstances`) | Nav default. ACID, Flyway migrations, and platform support. |
| Cache-only or ephemeral state | **No database** (Valkey or in-memory) | Avoid Cloud SQL cost and operations when data can be recreated. |
| Analytics, reporting, large aggregations | **BigQuery** (data platform) | For analytics, not operational workloads. |
| Event flow between services | **Kafka**, not a database | Rapids & Rivers or domain events. A database is not an integration mechanism. |

> **⚠️ Ask first** before introducing a database type not already used by the
> service; it affects operations, backups, and access control.

## HikariCP for NAIS containers

Size pools for NAIS replicas and Cloud SQL limits, not JVM defaults. Configure
`DataSource` explicitly; Hikari's default `maximumPoolSize = 10` is dangerous
for a service that can scale out.

```kotlin
// Configure from environment variables injected by gcp.sqlInstances (DB_JDBC_URL, DB_USERNAME, DB_PASSWORD)
fun dataSource(env: ApplicationEnvironment): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = env.config.property("db.jdbcUrl").getString()
        username = env.config.property("db.username").getString()
        password = env.config.property("db.password").getString()
        maximumPoolSize = 3      // Start small — 3–5 for typical Nav services
        minimumIdle = 1
        connectionTimeout = 10_000 // 10s — fail quickly if Cloud SQL Proxy is down
        idleTimeout = 300_000      // 5 min — release idle connections quickly
        maxLifetime = 1_800_000    // 30 min — below Cloud SQL's restart threshold
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
    })
```

`maxLifetime = 30 min` replaces connections before Cloud SQL Proxy forces a
break during maintenance or node replacement. Explicit `READ_COMMITTED` matches
the PostgreSQL default and avoids driver surprises. Cloud SQL sets
`max_connections` per tier and memory; shared-core is well below 100. With
`replicas.max = 4` and pool size 3, the maximum is 12, while 10 × 20 is 200 and
fails when it exceeds the limit. Development and production may use different tiers.

> **⚠️ Ask first** before `maximumPoolSize > 5`; it is almost always a symptom
> of slow queries or missing indexes, not a lack of pool capacity.

## SQL and Flyway catalog

- Index foreign-key columns and frequently filtered columns.
- Use `@>` plus a GIN index for JSONB containment, and `->>` for key lookup.
- Use `ON CONFLICT` only with an actual `UNIQUE` constraint; use CHECK/UNIQUE
  where the database can enforce domain rules.
- Batch fetch (`findByIdIn`) instead of N+1; select only needed columns and use
  LIMIT when results can grow. Do not use `SELECT *` in production code.
- Partitioning and advisory locks require discussion first.
- Follow `/flyway-migration`: `TIMESTAMPTZ`, foreign-key indexes, UUID with
  `gen_random_uuid()`, repeatable `R__*.sql` for views/functions/triggers, and
  forward-only reversal through a new `V{n+1}` migration.
- Put `CREATE INDEX CONCURRENTLY` in its own non-transactional migration.
- Coordinate destructive changes with consumer teams for shared schemas.

## Complete review checklist

- [ ] HikariCP: pool 3–5, `maxLifetime = 30 min`, explicit isolation.
- [ ] `replicas.max × maximumPoolSize ≤ max_connections` is verified.
- [ ] Database selection is confirmed (PostgreSQL operational, BigQuery analytics,
  Kafka integration).
- [ ] Consumer teams are notified before destructive shared-schema changes.
- [ ] Foreign-key/WHERE indexes, constraints, `CONCURRENTLY` when needed, and no N+1.
- [ ] No `SELECT *`; LIMIT is used where queries can become large.
- [ ] Reversal is forward-only through a new migration, never undo.
