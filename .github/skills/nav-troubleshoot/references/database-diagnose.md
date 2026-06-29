# Database-diagnose — Cloud SQL, HikariCP-pool og Flyway

Diagnostiske trær for database-problemer i dette Ktor-backendet (`no.nav.syfo`) med NAIS-provisjonert PostgreSQL (Cloud SQL). Connection-pool er HikariCP; migreringer kjøres med Flyway ved oppstart.

## Sjekk tilkobling

```bash
# Database-env-vars fra NAIS
kubectl get pod {pod} -n {namespace} \
  -o jsonpath='{range .spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' \
  | grep DB_
```

Typiske env-vars fra `gcp.sqlInstances`: `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD` (prefix kan være annet hvis `envVarPrefix` er satt — Ktor-config må lese samme prefix).

```bash
# Pool-status i logs (HikariCP)
kubectl logs -n {namespace} -l app={app-name} --tail=200 \
  | grep -i "hikari\|connection pool\|datasource"

# Flyway-status (ved startup)
kubectl logs -n {namespace} -l app={app-name} --tail=500 \
  | grep -i "flyway\|migration"
```

## Diagnostisk tre

```
Database-tilkoblingsfeil
├── Er Cloud SQL-instans oppe?
│   ├── Nei → sjekk GCP Console / Nais Console (Database-fane)
│   └── Ja → gå videre
├── Er env-vars satt i podden?
│   ├── DB_HOST / DB_PORT / DB_DATABASE mangler → sjekk `gcp.sqlInstances` i manifest (se /nais-manifest)
│   ├── Riktig `envVarPrefix`? → default er `DB`; hvis annet satt, må Ktor-config lese samme
│   └── Satt → gå videre
├── Feilet Flyway-migrasjon?
│   ├── "relation does not exist" → Flyway ikke kjørt; sjekk at migrering kjøres i startup
│   ├── "Migration failed" → SQL-feil i én migrasjonsfil. Se log for filnavn.
│   │   → Fiks SQL, ev. `flyway repair` (kun utvikler-assistert). Se /flyway-migration.
│   └── Nei → gå videre
├── Pool exhaustion?
│   ├── "Connection is not available, request timed out"
│   │   → Reduser `maximumPoolSize`, sjekk connection leaks (manglende close / leak i `use { }`)
│   ├── Mange "active" i HikariCP-metrikker
│   │   → Trege queries. Kjør EXPLAIN ANALYZE (se /postgresql-review)
│   └── Nei → gå videre
├── Cloud SQL max_connections nådd?
│   │   `replicas × maximumPoolSize` må være ≤ `max_connections` på instansen
│   ├── Ja → reduser pool per replica, eller øk instans-størrelse
│   └── Nei → gå videre
└── Nettverks-tilgang?
    ├── NAIS kjører Cloud SQL proxy som sidecar — sjekk at sidecar-container er Ready
    ├── "Connection refused: localhost:5432" → proxy ikke oppe → se pod-diagnose.md (sidecar)
    └── "Connection refused: 10.x.x.x" → app prøver direkte IP; bruk `DB_HOST` fra NAIS
```

## Vanlige NAV-spesifikke feilmønstre

| Feilmelding | Årsak | Løsning |
|------------|-------|---------|
| `Connection is not available, request timed out` | HikariCP pool exhaustion | Reduser `maximumPoolSize`; se connection leaks |
| `FATAL: too many connections for role` | `replicas × pool` > `max_connections` | Reduser pool per replica eller oppgrader instans |
| `FATAL: password authentication failed` | Feil credentials | NAIS genererer nye secrets ved rotasjon — redeploy appen |
| `Flyway migration ... failed` | SQL-feil i migrasjon | Fiks SQL i `V{nr}__{navn}.sql`; se /flyway-migration |
| `relation "tabell" does not exist` | Flyway ikke kjørt eller feil schema | Sjekk at Flyway kjører i startup og at schema stemmer |
| `Connection refused: localhost:5432` | Cloud SQL proxy-sidecar ikke oppe | `kubectl describe pod` — se at `cloudsql-proxy`-container er Ready |

## Pool-tommelfingerregler

- HikariCP `maximumPoolSize` × `replicas.max` **må være ≤** Cloud SQL `max_connections` (minus reservert for admin/Nais).
- Default HikariCP pool = 10 er ofte for høyt når `replicas.max: 4` på en `db-f1-micro`-instans.
- En treg query som holder en pool-slot = effektivt pool-lekkasje. Sjekk EXPLAIN ANALYZE (se /postgresql-review).

## Når dette peker på annet

- Pod krasjer pga. DB-problem ved oppstart → [pod-diagnose.md](./pod-diagnose.md)
- Trege queries, schema-valg, index-strategi → `/postgresql-review` / `/flyway-migration` (design-tid, ikke kjøre-tid)
- Fikse-disiplin (in-memory Postgres via Testcontainers) → `/diagnosing-bugs`
