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

## Nøkkelpunkter for migrasjoner

- Bruk `TIMESTAMPTZ` (ikke `TIMESTAMP`) for alle tidsstempel-kolonner
- Indekser på alle FK-kolonner
- UUID-primærnøkler med `gen_random_uuid()`
- `TEXT` i stedet for `VARCHAR`
- Egne migreringer for `CREATE INDEX CONCURRENTLY`
- Repeterbare migreringer (`R__*.sql`) for views, funksjoner og triggers

## TIMESTAMPTZ

```sql
-- ✅ Alltid TIMESTAMPTZ
CREATE TABLE vedtak (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bruker_id UUID NOT NULL,
    status TEXT NOT NULL,
    opprettet TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    oppdatert TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ❌ Aldri TIMESTAMP uten tidssone (mister tidssoneinformasjon)
```

## FK-indekser

```sql
-- ✅ Indeks på FK-kolonner for å unngå treg cascading og JOIN
CREATE TABLE vedtak (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sak_id UUID NOT NULL REFERENCES sak(id),
    bruker_id UUID NOT NULL
);

CREATE INDEX idx_vedtak_sak_id ON vedtak (sak_id);
CREATE INDEX idx_vedtak_bruker_id ON vedtak (bruker_id);
```

`CREATE INDEX` (uten `CONCURRENTLY`) er greit her fordi tabellen opprettes tom i samme migrering. For indekser på tabeller som allerede har data, bruk `CONCURRENTLY` i egen migrering.

## UUID-primærnøkler

```sql
-- ✅ UUID med gen_random_uuid()
CREATE TABLE hendelse (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type TEXT NOT NULL,
    data JSONB NOT NULL,
    opprettet TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

## Repeterbare migreringer

```sql
-- R__vedtak_view.sql — repeterbar migrering for views (kjøres på nytt når innholdet endres)
CREATE OR REPLACE VIEW aktive_vedtak AS
SELECT id, bruker_id, status, opprettet
FROM vedtak
WHERE status = 'AKTIV';
```

## Trestegs feltmigrasjon på delt schema

For delte Cloud SQL-instanser der andre team leser: bruk expand-migrate-contract i separate PR-er.

1. **Expand** — `V{n}__add_ny_kolonne.sql`: legg til ny kolonne (nullable / med default). Ingen konsumenter påvirkes.
2. **Migrate** — dual-write fra produsent, og konsumenter flytter lesing til ny kolonne én om gangen.
3. **Contract** — `V{n+k}__drop_gammel_kolonne.sql`: fjern gammel kolonne først når all produksjonstrafikk er bekreftet migrert (sjekk i 2+ uker).

> **🚫 Aldri** kjør `DROP COLUMN` / `ALTER COLUMN TYPE` på delt schema uten forhåndsvarsel og bekreftet konsument-migrasjon.
