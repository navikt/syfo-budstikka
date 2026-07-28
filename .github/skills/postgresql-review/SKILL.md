---
name: postgresql-review
description: "Review PostgreSQL and DataSource behavior. Use when work touches HikariCP or Cloud SQL capacity, connection failures, schemas, or interactions with Flyway migrations."
---

# PostgreSQL review

Review PostgreSQL usage in this repository. Cover Nav-specific connection-pool
sizing (NAIS replicas plus Cloud SQL), indexes, migrations, and coordination of
shared schemas.

Read [references/sql-patterns.md](references/sql-patterns.md) for Nav-specific
HikariCP and `gcp.sqlInstances` configuration, and
[references/migration-flyway.md](references/migration-flyway.md) for migration
patterns. Generic SQL tuning (index selection, JSONB, window functions, upsert,
partitioning, and N+1) is out of scope; use PostgreSQL documentation when needed.

## Workflow

1. Select technology from the actual need, and ask before introducing a new
   database type. Read [references/review-catalog.md](references/review-catalog.md).
2. Size HikariCP so `replicas.max × maximumPoolSize ≤ max_connections`; verify
   `SHOW max_connections;` for the environment's actual Cloud SQL tier. Start
   with pool size 3–5, `maxLifetime = 30 min`, and explicit `READ_COMMITTED`.
3. Read the HikariCP/NAIS wiring in
   [references/sql-patterns.md](references/sql-patterns.md) before configuration.
4. For Flyway or a shared schema, follow
   [references/migration-flyway.md](references/migration-flyway.md) and the
   catalog review checklist.

## Shared database — coordinate migrations

Several Nav teams may read from the same Cloud SQL instance. Schema changes are
then not local.

**Conditional guidance:** If another team reads your database, coordinate schema
changes with its consumer team before merge.

Check before destructive migrations:

- [ ] Are other applications or teams allowed to access this `gcp.sqlInstances`
  instance?
- [ ] Have `DROP COLUMN`, `ALTER COLUMN TYPE`, and `RENAME` been checked against
  consumer queries?
- [ ] Have consumers deployed code that tolerates the new schema before merge?

Use a **three-step field migration** (expand–migrate–contract) for shared schemas:

1. **Expand:** Add a new column or table. No consumer is affected.
2. **Migrate:** Dual-write from the producer; migrate consumer reads one at a time.
3. **Contract:** Remove the old column in a separate PR only after every consumer
   is confirmed migrated (check production traffic for at least two weeks).

> **🚫 Never** run `DROP COLUMN` or `ALTER COLUMN TYPE` on a shared schema
> without notice and confirmation from consumer teams. One deployment can take
> down another service.

The migration files themselves follow this repository's `flyway-migration`
skill. Also read [references/migration-flyway.md](references/migration-flyway.md).

## Boundaries

### ✅ Always

- Use HikariCP `maximumPoolSize` 3–5 and `maxLifetime = 30 min` for Cloud SQL.
- Verify `replicas × pool ≤ max_connections` before production deployment.
- Index foreign-key columns and frequently filtered columns.
- Use `TIMESTAMPTZ` for every timestamp column.
- Use LIMIT for queries that may return many rows.
- Notify consumer teams about shared-database schema changes.

### ⚠️ Ask first

- `maximumPoolSize > 5` (almost always a symptom, not the solution).
- A new database type (BigQuery or Valkey) in a service that does not use it.
- New indexes on large production tables; use `CONCURRENTLY` when appropriate.
- Partitioning or advisory locks in an existing solution.
- Destructive shared-schema migrations (`DROP COLUMN`, `ALTER TYPE`, `RENAME`).

### 🚫 Never

- Use `SELECT *` in production code.
- Create N+1 queries.
- Run `DROP TABLE` in production without a backup plan.
- Use `TIMESTAMP` without a timezone; use `TIMESTAMPTZ`.
- Drop a shared-schema column before confirmed consumer migration.
- Use a database as an integration mechanism between teams; use Kafka or an API.

## Connection to the delivery loop

When a database decision belongs to a planned change, lock the pool, schema, and
migration steps in the task-scoped brief. Record difficult, durable architecture
choices as ADRs under `docs/adr/`, for example PostgreSQL versus Kafka or
expand–migrate–contract for a shared schema. Verify pool sizing and migrations
with Testcontainers, and include command, result, and exit code in
`KOKK_RESULT`/the PR. Shared-schema, migration, or pool-configuration changes
are R3/R4 and require `grill-inspektor` review after Kokk before acceptance.

## Reference files

| File | Contents |
|-----|---------|
| [references/sql-patterns.md](references/sql-patterns.md) | Nav-specific HikariCP tuning and `gcp.sqlInstances` configuration |
| [references/migration-flyway.md](references/migration-flyway.md) | Migration patterns: TIMESTAMPTZ, FK indexes, UUID, CONCURRENTLY, repeatable migrations, and three-step field migration |
| [references/review-catalog.md](references/review-catalog.md) | Database selection, a HikariCP example, SQL/Flyway catalog, and the full review checklist |
