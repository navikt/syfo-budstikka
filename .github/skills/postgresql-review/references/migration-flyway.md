# Migrations and Flyway patterns

Migration patterns for this Nav Ktor backend. Read the
[review catalog](review-catalog.md) for principles and the checklist. The
`flyway-migration` skill in this repository owns file naming, placement, and
ordering.

## Production `CONCURRENTLY` indexes

`CREATE INDEX CONCURRENTLY` cannot run inside a transaction, so put it in its
own migration. This repository configures Flyway in code
(`Flyway.configure()...`); verify `executeInTransaction` there instead of
guessing global properties.

```sql
-- V3__create_index_concurrently.sql
-- flyway:executeInTransaction=false
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vedtak_bruker_status
ON vedtak (bruker_id, status);
```

If such a migration was interrupted, an invalid index with the same name may
remain. Remove it before another run — otherwise `IF NOT EXISTS` can hide the
problem rather than making reruns safe.

## Generic migration conventions → `/flyway-migration`

`/flyway-migration` owns file names (`V<n>__`), `TIMESTAMPTZ` over `TIMESTAMP`,
`UUID`/`gen_random_uuid()`, `TEXT` over `VARCHAR`, foreign-key indexes,
repeatable `R__` migrations, and forward-only discipline. This reference covers
only what a **review** must check in PostgreSQL context: interrupted
`CONCURRENTLY` indexes and shared-schema coordination.

## Three-step field migration for a shared schema

For shared Cloud SQL instances read by other teams, use expand–migrate–contract
in separate PRs.

1. **Expand** — `V{n}__add_ny_kolonne.sql`: add the new column (nullable or with
   a default). No consumer is affected.
2. **Migrate** — dual-write from the producer, then migrate consumer reads one at
   a time.
3. **Contract** — `V{n+k}__drop_gammel_kolonne.sql`: remove the old column only
   after all production traffic is confirmed migrated (check for at least two weeks).

> **🚫 Never** run `DROP COLUMN` or `ALTER COLUMN TYPE` on a shared schema
> without notice and confirmed consumer migration.
