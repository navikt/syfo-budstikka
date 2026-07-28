# Database diagnosis — Cloud SQL, HikariCP pool, and Flyway

Diagnosis trees for database failures in this Ktor backend (`no.nav.budstikka`)
with NAIS-provisioned PostgreSQL (Cloud SQL). HikariCP owns the connection pool;
Flyway runs migrations at startup.

## Check connectivity

```bash
# Database environment variables from NAIS
kubectl get pod {pod} -n {namespace} \
  -o jsonpath='{range .spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' \
  | grep DB_
```

Typical variables from `gcp.sqlInstances` are `DB_HOST`, `DB_PORT`,
`DB_DATABASE`, `DB_USERNAME`, and `DB_PASSWORD`. The prefix can differ when
`envVarPrefix` is set; Ktor configuration must read that same prefix.

```bash
# Pool status in HikariCP logs
kubectl logs -n {namespace} -l app={app-name} --tail=200 \
  | grep -i "hikari\|connection pool\|datasource"

# Flyway status at startup
kubectl logs -n {namespace} -l app={app-name} --tail=500 \
  | grep -i "flyway\|migration"
```

## Diagnosis tree

```text
Database connection failure
├── Is the Cloud SQL instance up?
│   ├── No → check GCP Console / NAIS Console (Database tab)
│   └── Yes → continue
├── Are environment variables set in the pod?
│   ├── DB_HOST / DB_PORT / DB_DATABASE missing → check manifest `gcp.sqlInstances` (see /nais-manifest)
│   ├── Correct `envVarPrefix`? → default is `DB`; if changed, Ktor config must read it
│   └── Set → continue
├── Did Flyway migration fail?
│   ├── "relation does not exist" → Flyway did not run; check startup migration
│   ├── "Migration failed" → SQL error in one migration file; inspect the filename in logs
│   │   → Fix SQL, possibly `flyway repair` with developer assistance only; see /flyway-migration
│   └── No → continue
├── Pool exhaustion?
│   ├── "Connection is not available, request timed out"
│   │   → Reduce `maximumPoolSize`; check leaks, missing close, or missing `use { }`
│   ├── Many "active" in HikariCP metrics
│   │   → Slow queries. Run EXPLAIN ANALYZE; see /postgresql-review
│   └── No → continue
├── Has Cloud SQL max_connections been reached?
│   │   `replicas × maximumPoolSize` must be ≤ instance `max_connections`
│   ├── Yes → reduce pool per replica or increase instance size
│   └── No → continue
└── Network access?
    ├── NAIS runs Cloud SQL proxy as sidecar; check that sidecar container is Ready
    ├── "Connection refused: localhost:5432" → proxy not up; see pod-diagnose.md
    └── "Connection refused: 10.x.x.x" → app uses direct IP; use NAIS `DB_HOST`
```

## Common Nav-specific failure patterns

| Error | Cause | Resolution |
|---|---|---|
| `Connection is not available, request timed out` | HikariCP pool exhaustion | Reduce `maximumPoolSize`; inspect connection leaks |
| `FATAL: too many connections for role` | `replicas × pool` > `max_connections` | Reduce pool per replica or upgrade instance |
| `FATAL: password authentication failed` | Wrong credentials | NAIS generates new secrets on rotation; redeploy application |
| `Flyway migration ... failed` | SQL error in migration | Fix SQL in `V{number}__{name}.sql`; see /flyway-migration |
| `relation "table" does not exist` | Flyway did not run or wrong schema | Verify startup Flyway and schema |
| `Connection refused: localhost:5432` | Cloud SQL proxy sidecar not running | `kubectl describe pod`; verify `cloudsql-proxy` is Ready |

## Pool rules of thumb

- HikariCP `maximumPoolSize` × `replicas.max` **must be ≤** Cloud SQL
  `max_connections`, minus capacity reserved for administration/NAIS.
- The default HikariCP pool of 10 is often too high with `replicas.max: 4` on a
  `db-f1-micro` instance.
- A slow query holding a pool slot is effectively a pool leak. Use EXPLAIN ANALYZE;
  see `/postgresql-review`.

## When this points elsewhere

- Pod crash caused by database failure at startup: [pod-diagnose.md](./pod-diagnose.md)
- Slow queries, schema choice, or index strategy: `/postgresql-review` /
  `/flyway-migration` (design time, not runtime)
- Fix discipline with in-memory Postgres through Testcontainers: `/diagnosing-bugs`
