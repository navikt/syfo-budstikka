# Database diagnosis — Cloud SQL, the HikariCP pool and Flyway

Diagnostic trees for database problems in this repository's Ktor backend (`no.nav.syfo`) with NAIS-provisioned PostgreSQL (Cloud SQL). The connection pool is HikariCP; migrations run with Flyway at startup.

## Check the connection

```bash
# Database env vars from NAIS
kubectl get pod {pod} -n {namespace} \
  -o jsonpath='{range .spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' \
  | grep DB_
```

Typical env vars from `gcp.sqlInstances`: `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD` (the prefix can be different if `envVarPrefix` is set — the Ktor config must read the same prefix).

```bash
# Pool status in the logs (HikariCP)
kubectl logs -n {namespace} -l app={app-name} --tail=200 \
  | grep -i "hikari\|connection pool\|datasource"

# Flyway status (at startup)
kubectl logs -n {namespace} -l app={app-name} --tail=500 \
  | grep -i "flyway\|migration"
```

## Diagnostic tree

```
Database connection failure
├── Is the Cloud SQL instance up?
│   ├── No → check the GCP Console / Nais Console (Database tab)
│   └── Yes → continue
├── Are the env vars set in the pod?
│   ├── DB_HOST / DB_PORT / DB_DATABASE missing → check `gcp.sqlInstances` in the manifest (see /nais-manifest)
│   ├── Correct `envVarPrefix`? → the default is `DB`; if another is set, the Ktor config must read the same one
│   └── Set → continue
├── Did a Flyway migration fail?
│   ├── "relation does not exist" → Flyway has not run; check that migration runs at startup
│   ├── "Migration failed" → SQL error in one migration file. See the log for the file name.
│   │   → Fix the SQL, possibly `flyway repair` (developer-assisted only).
│   └── No → continue
├── Pool exhaustion?
│   ├── "Connection is not available, request timed out"
│   │   → Reduce `maximumPoolSize`, check for connection leaks (missing close / leak in `use { }`)
│   ├── Many "active" in the HikariCP metrics
│   │   → Slow queries. Run EXPLAIN ANALYZE (see /postgresql-review)
│   └── No → continue
├── Cloud SQL max_connections reached?
│   │   `replicas × maximumPoolSize` must be ≤ `max_connections` on the instance
│   ├── Yes → reduce the pool per replica, or increase the instance size
│   └── No → continue
└── Network access?
    ├── NAIS runs the Cloud SQL proxy as a sidecar — check that the sidecar container is Ready
    ├── "Connection refused: localhost:5432" → the proxy is not up → see pod-diagnose.md (sidecar)
    └── "Connection refused: 10.x.x.x" → the app is trying a direct IP; use `DB_HOST` from NAIS
```

## Common NAV-specific failure patterns

| Error message | Cause | Fix |
|------------|-------|---------|
| `Connection is not available, request timed out` | HikariCP pool exhaustion | Reduce `maximumPoolSize`; look for connection leaks |
| `FATAL: too many connections for role` | `replicas × pool` > `max_connections` | Reduce the pool per replica or upgrade the instance |
| `FATAL: password authentication failed` | Wrong credentials | NAIS generates new secrets on rotation — redeploy the app |
| `Flyway migration ... failed` | SQL error in a migration | Fix the SQL in `V{nr}__{navn}.sql` |
| `relation "tabell" does not exist` | Flyway has not run, or wrong schema | Check that Flyway runs at startup and that the schema is correct |
| `Connection refused: localhost:5432` | Cloud SQL proxy sidecar not up | `kubectl describe pod` — verify that the `cloudsql-proxy` container is Ready |

## Pool rules of thumb

- HikariCP `maximumPoolSize` × `replicas.max` **must be ≤** Cloud SQL `max_connections` (minus what is reserved for admin/Nais).
- The default HikariCP pool of 10 is often too high when `replicas.max: 4` on a `db-f1-micro` instance.
- A slow query holding a pool slot = effectively a pool leak. Check EXPLAIN ANALYZE (see /postgresql-review).

## When this points elsewhere

- The pod crashes because of a DB problem at startup → [pod-diagnose.md](./pod-diagnose.md)
- Slow queries, schema choices, index strategy → `/postgresql-review` (design time, not runtime)
- Fix discipline (in-memory Postgres via Testcontainers) → `/diagnosing-bugs`
