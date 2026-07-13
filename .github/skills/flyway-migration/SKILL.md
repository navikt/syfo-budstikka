---
name: flyway-migration
description: "Flyway-databasemigreringer i no.nav.syfo-backend — V__ SQL-filer, schema-endringer, backfill, indeksmigrering, rollback-plan og produksjonssikker rekkefølge. Brukes når du oppretter eller endrer en migrering, legger til/endrer tabell, kolonne eller indeks i Postgres."
---

# Flyway-migrering

Opprett eller endre en Flyway-migreringsfil etter teamets konvensjoner i dette repoet.

## Steg

1. Finn migreringsmappen ved å søke etter eksisterende `V*__*.sql`-filer under `src/main/resources/db/migration/` (Flyway-standard), eller sjekk `flyway.locations` / Flyway-konfig i `src/main/kotlin` der `DataSource` settes opp. List eksisterende migreringer for å finne neste versjonsnummer.
2. Les den nyeste migreringen for å forstå navngivings- og stilkonvensjonene i akkurat dette repoet.
3. Opprett den nye migreringsfilen med riktig navn: `V{next}__{beskrivelse}.sql`. Hvis det ikke finnes migreringer fra før, start på `V1__init.sql` og legg dem under `src/main/resources/db/migration/`.

## Konvensjoner

- Foretrekk fail-fast i versjonerte migreringer — bruk `IF NOT EXISTS` / `IF EXISTS` bare når du bevisst vil gjøre migreringen idempotent
- Bruk `TIMESTAMPTZ` for tidsstempler (med `DEFAULT NOW()`)
- Bruk `UUID` med `gen_random_uuid()` for primærnøkler der det passer
- Bruk `TEXT` i stedet for `VARCHAR`
- Legg til indekser for kolonner det søkes ofte på
- Bruk `CREATE INDEX CONCURRENTLY IF NOT EXISTS` for nye indekser på eksisterende tabeller, men behandle avbrudd eksplisitt: en ugyldig indeks med samme navn kan bli liggende igjen
- Bruk vanlig `CREATE INDEX` bare når indeksen opprettes sammen med en ny tom tabell i samme migrering
- Én fokusert endring per migrering
- Ikke rediger en `V__`-migrering som allerede er kjørt i et miljø — Flyway feiler på endret checksum. Lag en ny migrering i stedet.
- `V__`-migreringer er **forward-only**: Flyway Community har ingen automatisk undo (`U__`-undo er en betalt funksjon). Et tilbakerull er en ny `V{n+1}__`-migrering som reverserer — aldri redigering eller sletting av en kjørt migrering.

## Mal

```sql
-- V{number}__{beskrivelse}.sql
CREATE TABLE table_name (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

CREATE INDEX idx_table_name_field ON table_name(field);
```

Bruk eksempelet over bare når tabellen opprettes i samme migrering og fortsatt er tom.

## Indekser på eksisterende tabeller

Bruk `CREATE INDEX CONCURRENTLY IF NOT EXISTS` for nye indekser på eksisterende tabeller, også når brukeren ikke sier at tabellen er stor. Dette er standardvalget i PostgreSQL-migreringer som treffer tabeller med data.

```sql
-- V5__add_index_concurrently.sql
-- NB: CREATE INDEX CONCURRENTLY kan ikke kjøre i transaksjon
-- Legg denne i egen migrering og verifiser Flyway-oppsettet først
-- flyway:executeInTransaction=false
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vedtak_bruker ON vedtak (bruker_id);
```

`CREATE INDEX CONCURRENTLY IF NOT EXISTS` må ligge i egen migrering og ikke kjøre i transaksjon. Hvis en slik migrering ble avbrutt, må du sjekke om en ugyldig indeks med samme navn ligger igjen og rydde den opp før ny kjøring; `IF NOT EXISTS` kan ellers skjule problemet i stedet for å gjøre re-kjøring trygg. I dette repoet konfigureres Flyway i kode (`Flyway.configure()...`); verifiser `executeInTransaction`/configuration der i stedet for å gjette på globale properties.

## Langvarige migreringer og NAIS

Hvis migreringen kan ta tid, sjekk appens NAIS-manifest (`.nais/`, `nais/` eller tilsvarende `*.yaml`). Verifiser at appen har `spec.startup` slik at Flyway får tid til å fullføre før liveness overtar og poden restartes midt i migreringen.

Hvis startup-probe mangler, foreslå eller legg den til med samme health-path som appen allerede bruker (typisk `/internal/isalive` eller `/isAlive` i et Ktor-backend):

```yaml
spec:
  liveness:
    path: /internal/isalive
  readiness:
    path: /internal/isready
  startup:
    path: /internal/isalive
    initialDelay: 10
    periodSeconds: 5
    failureThreshold: 60
```

Informer bruker om hvor lang tid startup-proben gir migreringen (`periodSeconds * failureThreshold`), enten den finnes fra før eller settes av ditt forslag.

Ikke endre liveness/readiness unødvendig. Målet er å unngå at poden blir restartet før migreringen er ferdig.

## Repeterbare migreringer

`R__*.sql`-filer kjøres på nytt hver gang innholdet endres.

Bruk dem for:

- views
- funksjoner
- triggers
- seed data

Hold versjonerte `V__`-migreringer uendrede, og bruk repeterbare migreringer for objekter som naturlig regenereres.

Eksempel på `updated_at`-trigger i en repeterbar migrering:

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

## Testcontainers-eksempel

Bruk Testcontainers for å verifisere at migrasjoner faktisk kan kjøres mot en ekte PostgreSQL-instans i tester — ikke bare H2 eller mock.

```kotlin
@Testcontainers
class DatabaseMigrationTest {
    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("testdb")
    }

    @Test
    fun `migrasjoner kjorer uten feil`() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()
    }
}
```

Dette gir rask tilbakemelding på at migrasjonsrekkefølge, SQL-syntaks og Flyway-konfig faktisk fungerer sammen, før koden treffer dev/prod i GCP.

## Kobling til faseløkken

Når en migrering inngår i en planlagt endring, noter schema-beslutninger (ny tabell/indeks, backfill-strategi, rollback) i `docs/context.md`, og fang varige valg (f.eks. UUID vs. bigserial, concurrently-indeksering) som en ADR under `docs/adr/`. Verifiser at migreringen kjører grønt via Testcontainers og legg evidensen i `.grill/VERIFICATION.md` før PR. For endringer som rører migreringer er det verdt å kjøre en ekstra review (`grill-inspektor`) før merge.
