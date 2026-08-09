---
name: postgresql-review
description: "Use when setting up or changing the DataSource/connection pool in a Nav backend, when you see connection errors in the log, when choosing database technology, or when reviewing a schema/migration change in Postgres. Covers HikariCP pooling and sizing against Cloud SQL/replicas, the interplay with Flyway, and coordination of shared schemas."
---

# PostgreSQL review

A review of PostgreSQL usage in this repository. Covers NAV-specific connection pool sizing (NAIS replicas + Cloud SQL), indexes, anti-patterns, migrations and coordination of shared schemas.

See [references/sql-patterns.md](references/sql-patterns.md) for NAV-specific HikariCP and `gcp.sqlInstances` setup, and [references/migration-flyway.md](references/migration-flyway.md) for migration patterns. Generic SQL tuning (index choice, JSONB, window functions, upsert, partitioning, N+1) is out of scope; use the PostgreSQL documentation or the team's established practice.

## Database decision tree in a NAV context

The NAV default is PostgreSQL via `gcp.sqlInstances` in the NAIS manifest. Choose the technology before you write code:

| Need | Choice | Rationale |
|-------|------|-----------|
| Transactional state, CRUD, case processing | **PostgreSQL** (`gcp.sqlInstances`) | NAV default. ACID, Flyway migrations, well supported on the platform. |
| Cache or ephemeral state only | **No DB** (Valkey or in-memory) | Avoid Cloud SQL cost and operations if the data can be recreated. |
| Analytics, reporting, large aggregations | **BigQuery** (data platform) | For the data platform/analytics — not for operational use. |
| Event flow between services | **Kafka**, not a DB | Rapids & Rivers / domain events. A database is not an integration mechanism. |

> **⚠️ Ask first** before introducing a new database type into a service that does not already have one — it affects operations, backup and access control.

## HikariCP for NAIS containers

The pool size must be adapted to the NAIS replicas and the Cloud SQL limits, not to JVM defaults. Configure the `DataSource` explicitly — do not lean on Hikari's defaults (`maximumPoolSize = 10`), which are dangerous for a service that can be scaled out.

```kotlin
// In this repository: gcp.sqlInstances (envVarPrefix: BUDSTIKKA_DB) injects BUDSTIKKA_DB_URL,
// BUDSTIKKA_DB_USERNAME, BUDSTIKKA_DB_PASSWORD (+ _HOST/_PORT/_DATABASE/_SSLKEY_PK8), which
// application.conf maps in its database { } block. Config.kt and Module.kt under
// src/main/kotlin/no/nav/budstikka/infrastructure/database/config/ build the HikariConfig from it.
fun createDataSource(config: DatabaseConfig): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.jdbcUrl                     // "jdbc:" + BUDSTIKKA_DB_URL, credentials stripped
        username = config.username
        password = config.password
        maximumPoolSize = config.maximumPoolSize     // Start small — 3–5 for typical NAV services
        minimumIdle = config.minimumIdle
        connectionTimeout = 10_000                   // 10s — fail fast when the Cloud SQL Proxy is down
        idleTimeout = 300_000                        // 5 min — release idle connections quickly
        maxLifetime = 1_800_000                      // 30 min — below Cloud SQL's restart threshold
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
    })
```

**Sizing formula:**

```
replicas.max × maximumPoolSize ≤ max_connections
```

Cloud SQL sets `max_connections` according to the instance tier/memory — smaller tiers (shared-core such as `db-f1-micro`) are well below 100, so verify with `SHOW max_connections;` before you do the maths (dev and prod often run different tiers). With `replicas.max = 4` and `maximumPoolSize = 3` you use at most 12 — safe even on a small tier. With `replicas.max = 10` and `maximumPoolSize = 20` you use 200 — the service falls over as soon as that exceeds `max_connections`.

**Rationale for `maxLifetime = 30 min`:** The Cloud SQL Proxy can restart (maintenance, node swaps). With a lower lifetime, connections are replaced before the proxy forces a break, so you avoid "connection reset" errors in the application log.

**Rationale for `transactionIsolation`:** An explicit `READ_COMMITTED` matches the PostgreSQL default and avoids surprises when driver defaults change between versions.

> **⚠️ Ask first** before raising `maximumPoolSize` above 5 — it is nearly always a symptom of slow queries or missing indexes, not of a pool shortage.

See [references/sql-patterns.md](references/sql-patterns.md) for the complete HikariCP and `gcp.sqlInstances` setup.

## Shared database — coordinating migrations

Several NAV teams often read from the same Cloud SQL instance (shared domain data). Schema changes are then not local.

**Conditional advice:** If other teams read from your database, coordinate schema changes with the consuming teams BEFORE merge.

Check before destructive migrations:

- [ ] Are there other apps/teams with access to this `gcp.sqlInstances` instance?
- [ ] Did you check `DROP COLUMN`, `ALTER COLUMN TYPE`, `RENAME` against the consumers' queries?
- [ ] Have the consumers deployed code that tolerates the new schema before you merge?

Use a **three-step field migration** (expand-migrate-contract) for shared schemas:

1. **Expand:** Add the new column/table. No consumers are affected.
2. **Migrate:** Dual-write from the producer, consumers move their reads to the new column one at a time.
3. **Contract:** Remove the old column in a separate PR once all consumers are confirmed migrated (check production traffic for 2+ weeks).

> **🚫 Never** run `DROP COLUMN` or `ALTER COLUMN TYPE` on a shared schema without prior notice and confirmation from the consuming teams. A single deploy can take down other people's services.

See also [references/migration-flyway.md](references/migration-flyway.md).

## SQL tuning and migrations

Index strategies, JSONB patterns, `ON CONFLICT`, constraints, `TIMESTAMPTZ`, UUID keys
and anti-patterns (N+1, `SELECT *`, missing `LIMIT`) are generic PostgreSQL knowledge —
read the repository's existing migrations and schema for the local style instead of a list here.

Two things that are not obvious, and that a review must catch:

- `CREATE INDEX CONCURRENTLY` must live in its own migration outside a transaction, and an
  aborted call leaves behind an invalid index with the same name that blocks the next attempt.
- Partitioning and advisory locks: **⚠️ Ask first** before introducing them into an
  existing solution.

See [references/migration-flyway.md](references/migration-flyway.md) for shared-schema coordination.

## Checklist

- [ ] HikariCP: `maximumPoolSize` 3–5, `maxLifetime = 30 min`, `transactionIsolation` set explicitly
- [ ] Sizing: `replicas.max × maximumPoolSize ≤ max_connections`
- [ ] Database choice confirmed (PostgreSQL for operational use, BigQuery for analytics, Kafka for integration)
- [ ] Shared schema? Consuming teams notified before destructive changes
- [ ] Indexes on all FK columns and frequently used WHERE columns
- [ ] `CREATE INDEX CONCURRENTLY` considered for new prod indexes on large tables
- [ ] CHECK/UNIQUE constraints used where domain rules can be enforced in the database
- [ ] No N+1 queries
- [ ] SELECT only the columns needed
- [ ] LIMIT on queries that can return many rows
- [ ] Revert path considered (forward-only: a new `V{n+1}` migration, not an undo)
- [ ] No `SELECT *` in production code

## Boundaries

### ✅ Always
- HikariCP `maximumPoolSize` 3–5, `maxLifetime = 30 min` for Cloud SQL
- Verify `replicas × pool ≤ max_connections` before a prod deploy
- Indexes on FK columns and frequent WHERE columns
- `TIMESTAMPTZ` for all timestamp columns
- LIMIT on queries that can return many rows
- Notify consuming teams about schema changes on a shared database

### ⚠️ Ask first
- `maximumPoolSize > 5` (nearly always a symptom, not a solution)
- A new database type (BigQuery, Valkey) in a service that does not have one
- New indexes on large tables in production — use `CONCURRENTLY` when needed
- Partitioning or advisory locks in existing solutions
- Destructive migrations (`DROP COLUMN`, `ALTER TYPE`, `RENAME`) on shared schemas

### 🚫 Never
- `SELECT *` in production code
- N+1 queries
- `DROP TABLE` in production without a backup plan
- `TIMESTAMP` without a time zone (use `TIMESTAMPTZ`)
- `DROP COLUMN` on a shared schema without a confirmed consumer migration
- Using the database as an integration mechanism between teams (use Kafka/API)

## Link to the phase loop

When a database decision is part of a planned change, put the task scope in the
issue/plan and the maintained database and schema detail in the relevant topic document.
When a lasting architectural choice passes the ADR gate — for example PostgreSQL vs.
Kafka for an integration need, or expand-migrate-contract for a shared
schema — recommend the documented route and wait for the user's choice before
`/domain-modeling` records it. Mirror the pool and migration steps in the active
plan. Verify the pool sizing and that migrations run green
(Testcontainers), and return the evidence to the active task before the PR.
Changes that touch shared schemas or pool config follow the canonical R3/R4
gate in `.github/copilot-instructions.md`.

## Reference files

| File | Contents |
|-----|---------|
| [references/sql-patterns.md](references/sql-patterns.md) | NAV-specific HikariCP tuning and `gcp.sqlInstances` setup |
| [references/migration-flyway.md](references/migration-flyway.md) | Migration patterns: TIMESTAMPTZ, FK indexes, UUID, CONCURRENTLY, repeatable migrations, three-step field migration |
