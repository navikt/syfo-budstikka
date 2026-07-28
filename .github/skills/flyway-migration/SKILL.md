---
name: flyway-migration
description: "Design production-safe Flyway migrations. Use when adding or changing tables, columns, indexes, backfills, migration ordering, or rollback plans."
---

# Flyway migration

Create or change a Flyway migration file using this repository's conventions.

## Steps

1. Find existing `V*__*.sql` files under
   `src/main/resources/database.migration/` and read `DataSource.migrate()` for
   active location. List migrations to find the next version number.
2. Read the newest migration to understand repository naming and style.
3. Create `V{next}__{description}.sql` in
   `src/main/resources/database.migration/`.

## Conventions

- Prefer fail-fast versioned migrations; use `IF NOT EXISTS` / `IF EXISTS` only
  when deliberate idempotency is required.
- Use `TIMESTAMPTZ` with `DEFAULT NOW()`, suitable `UUID`/`gen_random_uuid()`
  primary keys, and `TEXT` rather than `VARCHAR`.
- Index frequently searched columns. Use `CREATE INDEX CONCURRENTLY IF NOT EXISTS`
  for an existing table; handle interrupted invalid indexes explicitly. Use plain
  `CREATE INDEX` only with a new empty table in the same migration.
- Keep one focused change per migration. Never edit a deployed `V__` migration:
  checksums fail. `V__` is forward-only; rollback is a new reversing `V{n+1}__`.

## Mal

```sql
-- V{number}__{description}.sql
CREATE TABLE table_name (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

CREATE INDEX idx_table_name_field ON table_name(field);
```

Use this only when the table is created in the same migration and remains empty.

## Indexes on existing tables

Use `CREATE INDEX CONCURRENTLY IF NOT EXISTS` for a new index on an existing table,
even when table size is unknown. This is the default for PostgreSQL migrations on
tables with data.

```sql
-- V5__add_index_concurrently.sql
-- NOTE: CREATE INDEX CONCURRENTLY cannot run in a transaction
-- Put this in its own migration and verify Flyway setup first
-- flyway:executeInTransaction=false
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vedtak_bruker ON vedtak (bruker_id);
```

It must be a separate non-transactional migration. After interruption, inspect and
remove an invalid index with the same name before retrying: `IF NOT EXISTS` can
hide the problem. This repository configures Flyway in code
(`Flyway.configure()...`); verify `executeInTransaction` there rather than
guessing global properties.

## Long-running migrations and NAIS

For a long migration, inspect `nais/nais-dev.yaml` and `nais/nais-prod.yaml`.
Verify startup/probe timing gives Flyway time to finish before liveness restarts
the pod mid-migration.

If the startup window is too short, update existing probes with the actual
syfo-budstikka paths:

```yaml
spec:
  liveness:
    path: /internal/health/is_alive
  readiness:
    path: /internal/health/is_ready
  startup:
    path: /internal/health/is_alive
    initialDelay: 10
    periodSeconds: 5
    failureThreshold: 60
```

Tell the user how much migration time the startup probe grants
(`periodSeconds * failureThreshold`). Do not change liveness/readiness without
need; the goal is to avoid restart before migration completes.

## Repeatable migrations

`R__*.sql` files rerun whenever their contents change.

Use them for:

- views
- funksjoner
- triggers
- seed data

Keep versioned `V__` migrations unchanged and use repeatable migrations for
objects that naturally regenerate.

Example `updated_at` trigger in a repeatable migration:

```sql
-- R__update_updated_at.sql
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';
```

## Testcontainers example

Use Testcontainers to prove migrations run against real PostgreSQL, not only H2
or mocks.

```kotlin
@Testcontainers
class DatabaseMigrationTest {
    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("testdb")
    }

    @Test
    fun `migrations run without failures`() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()
    }
}
```

This quickly proves migration ordering, SQL syntax, and Flyway configuration work
together before dev/prod GCP.

## Delivery flow

For planned work, lock schema, backfill, and rollback in the brief; record
hard-to-reverse choices such as UUID versus bigserial or concurrent indexing as
ADRs. Verify with Testcontainers and include command, result, and exit code in
KOKK_RESULT/PR. Migrations are R3/R4 and require `grill-inspektor` after Kokk.
