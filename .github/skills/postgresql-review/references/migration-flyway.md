# Migrations and Flyway patterns

Migration patterns for PostgreSQL in a NAV backend. File naming, placement and ordering follow the Flyway standard and the repository's existing migrations.

## CONCURRENTLY indexes in production

`CREATE INDEX CONCURRENTLY` cannot run inside a transaction, so it must live in its own migration. In this repository Flyway is configured in code (`Flyway.configure()...`) — verify `executeInTransaction` there instead of guessing at global properties.

```sql
-- V3__create_index_concurrently.sql
-- flyway:executeInTransaction=false
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vedtak_bruker_status
ON vedtak (bruker_id, status);
```

If such a migration was aborted, an invalid index with the same name may be left behind. Clean it up before the next run — otherwise `IF NOT EXISTS` can hide the problem instead of making the re-run safe.

## Generic migration conventions

File naming (`V<n>__`), `TIMESTAMPTZ` over `TIMESTAMP`, `UUID`/`gen_random_uuid()`, `TEXT` over `VARCHAR`, FK indexes, repeatable `R__` migrations and the forward-only discipline are Flyway standard — read the repository's existing migrations for the local style. This reference covers only what a **review** should look for in a Postgres context: an aborted `CONCURRENTLY` (above) and shared-schema coordination (below).

## Three-step field migration on a shared schema

For shared Cloud SQL instances where other teams read: use expand-migrate-contract in separate PRs.

1. **Expand** — `V{n}__add_ny_kolonne.sql`: add the new column (nullable / with a default). No consumers are affected.
2. **Migrate** — dual-write from the producer, and consumers move their reads to the new column one at a time.
3. **Contract** — `V{n+k}__drop_gammel_kolonne.sql`: remove the old column only once all production traffic is confirmed migrated (check for 2+ weeks).

> **🚫 Never** run `DROP COLUMN` / `ALTER COLUMN TYPE` on a shared schema without prior notice and a confirmed consumer migration.
