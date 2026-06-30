# Migrasjoner og Flyway-mønstre

Migrasjonsmønstre for PostgreSQL i dette NAV Ktor-backendet. Se [SKILL.md](../SKILL.md) for prinsipper og sjekkliste. Selve filnavngiving, plassering og rekkefølge dekkes av `flyway-migration`-skillen i repoet.

## CONCURRENTLY-indekser i produksjon

`CREATE INDEX CONCURRENTLY` kan ikke kjøre i en transaksjon, så den må ligge i egen migrering. I dette repoet konfigureres Flyway i kode (`Flyway.configure()...`) — verifiser `executeInTransaction` der i stedet for å gjette på globale properties.

```sql
-- V3__create_index_concurrently.sql
-- flyway:executeInTransaction=false
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vedtak_bruker_status
ON vedtak (bruker_id, status);
```

Hvis en slik migrering ble avbrutt, kan en ugyldig indeks med samme navn ligge igjen. Rydd den opp før ny kjøring — `IF NOT EXISTS` kan ellers skjule problemet i stedet for å gjøre re-kjøring trygg.

## Generiske migreringskonvensjoner → `/flyway-migration`

Filnavngiving (`V<n>__`), `TIMESTAMPTZ` over `TIMESTAMP`, `UUID`/`gen_random_uuid()`, `TEXT` over `VARCHAR`, FK-indekser, repeterbare `R__`-migreringer og forward-only-disiplinen eies av `/flyway-migration` — ikke dupliser dem her. Denne referansen dekker bare det en **review** skal se etter i Postgres-kontekst: avbrutt `CONCURRENTLY` (over) og delt-schema-koordinering (under).

## Trestegs feltmigrasjon på delt schema

For delte Cloud SQL-instanser der andre team leser: bruk expand-migrate-contract i separate PR-er.

1. **Expand** — `V{n}__add_ny_kolonne.sql`: legg til ny kolonne (nullable / med default). Ingen konsumenter påvirkes.
2. **Migrate** — dual-write fra produsent, og konsumenter flytter lesing til ny kolonne én om gangen.
3. **Contract** — `V{n+k}__drop_gammel_kolonne.sql`: fjern gammel kolonne først når all produksjonstrafikk er bekreftet migrert (sjekk i 2+ uker).

> **🚫 Aldri** kjør `DROP COLUMN` / `ALTER COLUMN TYPE` på delt schema uten forhåndsvarsel og bekreftet konsument-migrasjon.
