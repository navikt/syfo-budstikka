---
name: nais-manifest
description: "Use when this Ktor backend (no.nav.syfo) needs a new or changed NAIS manifest — nais.yaml for an Application or a Naisjob: ingress, resources, probes, accessPolicy, Azure AD/TokenX, Kafka or GCP Postgres. Trigger: 'create a nais manifest', 'expose the app', 'add database/Kafka/auth to nais', 'scale up in prod', CPU throttling, OOM kill, batch/cron job. Invoked via /nais-manifest."
---

# NAIS manifest

Create or update a complete NAIS manifest (`Application` for the continuously running service, `Naisjob` for batch). Focus on the server port, the JVM runtime and JVM observability, not the frontend.

## Procedure

1. **Read the existing manifests first** under `.nais/`, `nais/` or equivalent `*.yaml`. Reuse the actual `namespace`, `labels.team`, health paths and prometheus path — do not assume values.
2. If the repository has no manifest yet: use the team namespace from `.github/copilot-instructions.md` / README, and follow the templates below.
3. Map out what the app actually needs: database (Postgres), Kafka, auth (TokenX/Azure AD), ingress, scaling — or whether it is a batch job (`Naisjob` instead of `Application`).
4. Use environment-specific manifests (`*-dev.yaml`, `*-prod.yaml`) when the repository already follows that pattern.

## Application template

```yaml
apiVersion: nais.io/v1alpha1
kind: Application
metadata:
  name: syfo-budstikka
  namespace: team-esyfo          # Read from the existing manifest
  labels:
    team: team-esyfo
spec:
  image: {{ image }}
  port: 8080                     # Ktor/Netty listens on this

  prometheus:
    enabled: true
    path: /internal/prometheus   # Check the actual path in the Ktor routing
  liveness:
    path: /internal/isalive      # Check the existing one — may also be /isAlive
    initialDelay: 5
  readiness:
    path: /internal/isready
    initialDelay: 5

  resources:
    requests:
      cpu: 50m
      memory: 256Mi
    limits:
      memory: 512Mi              # Never set cpu limits — see the rule below
```

**Important:** Verify `port`, `prometheus.path`, `liveness.path` and `readiness.path` against the Ktor routing (`embeddedServer`/`routing { ... }`) before you commit. The endpoints must exist in the code.

## Rule: no CPU limits in NAIS

**Never** set `resources.limits.cpu` — only `requests.cpu`.

**Why:** the Kubernetes CFS quota enforces CPU limits in 100 ms windows. When a container briefly hits the limit, the whole container is throttled for the rest of the window — including threads that do not need CPU. On the JVM this hits extra hard during startup (JIT compilation, class loading) and GC, and produces latency tails and timeouts. NAIS recommends `requests.cpu` for scheduling and lets the node handle actual consumption.

Memory limits, on the other hand, **must be set** — without a limit a container can take down the whole node via OOM.

## Resource starting points (JVM)

| Size    | `requests.cpu` | `requests.memory` | `limits.memory` |
|---------|----------------|-------------------|-----------------|
| Small   | 50m            | 256Mi             | 512Mi           |
| Medium  | 100m           | 512Mi             | 1Gi             |
| Large   | 200m           | 1Gi               | 2Gi             |

The JVM needs headroom above the heap for metaspace, threads and direct buffers — set `limits.memory` above `-Xmx`. Adjust based on actual consumption in Grafana. `replicas: { min: 2, max: 4, cpuThresholdPercentage: 80 }` is a reasonable starting point for prod.

## accessPolicy — always explicit

Define both `inbound` and `outbound`. Do not forget `namespace` (and `cluster` for calls across clusters):

```yaml
accessPolicy:
  inbound:
    rules:
      - application: kallende-app
        namespace: team-kallende
      - application: annen-tjeneste
        namespace: annet-team
        cluster: prod-gcp
  outbound:
    rules:
      - application: pdl-api
        namespace: pdl
      - application: syfo-nedstroms
        namespace: team-esyfo
    external:
      - host: api.ekstern-tjeneste.no
```

`accessPolicy` must be kept in sync with the auth code: incoming tokens that do not match `inbound.rules` are rejected at platform level. Drift between manifest and code is a bug. Auth mechanisms and audience format: see `/auth-overview`.

## PostgreSQL (GCP SQL)

```yaml
gcp:
  sqlInstances:
    - type: POSTGRES_17          # Check the version used in this repository
      tier: db-f1-micro          # dev; prod: db-custom-1-3840
      highAvailability: false    # prod: true
      diskAutoresize: false      # prod: true
      databases:
        - name: budstikka-db
          envVarPrefix: DB
```

Provides env vars: `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD`. Schema changes and migrations are run with Flyway. For long-running migrations: make sure there is a `startup` probe so that Flyway finishes before liveness restarts the pod.

### HikariCP pool — owned by `/postgresql-review`

Connection pool sizing (`maximumPoolSize` 3–5 in containers, not the default 10; `maxLifetime`; and the rule `replicas.max × maximumPoolSize ≤ max_connections`) belongs to `/postgresql-review`. The manifest feeds into that calculation: `replicas.max` and the chosen `gcp.sqlInstances` tier determine, respectively, how many poolers exist and what `max_connections` is — keep them in sync (more replicas or a smaller tier tightens the pool budget).

## Kafka

```yaml
kafka:
  pool: nav-dev                  # Or nav-prod
```

Topic naming convention: `{team}.{domain}.v{version}`. Dev: `partitions: 1, replication: 1`. Prod: `partitions: 6+, replication: 3`. A continuous queue consumer belongs in an `Application`, not a `Naisjob`.

## Azure AD and TokenX

```yaml
azure:
  application:
    enabled: true
    tenant: nav.no               # Or trygdeetaten.no

tokenx:
  enabled: true                  # On-behalf-of for the logged-in user
```

- User/person context inbound and passed further downstream → **TokenX (OBO)**.
- Pure machine-to-machine without person context → **Azure AD client_credentials**, or **Maskinporten** for external organizations.
- A partner acting on behalf of a business in Altinn → **Altinn 3 system user** (Maskinporten + system-user token).

Choice of mechanism, token validation in Ktor and audience format are covered by `/auth-overview` — do not duplicate the auth details here.

## Ingress — choose the right domain

| Domain             | Use                                                        |
|--------------------|------------------------------------------------------------|
| `*.nav.no`         | Public-facing user interfaces                              |
| `*.intern.nav.no`  | Internal employee interfaces (requires Nav network/naisdevice) |
| `*.ekstern.nav.no` | External user interfaces not hosted on nav.no              |

Dev variants: `*.dev.nav.no`, `*.intern.dev.nav.no`, `*.ekstern.dev.nav.no`. A pure API that is only called by other Nav apps often needs **no** ingress — use `accessPolicy.inbound` instead.

```yaml
ingresses:
  - https://syfo-budstikka.intern.dev.nav.no
```

## Observability (auto-instrumentation)

```yaml
observability:
  autoInstrumentation:
    enabled: true
    runtime: java
```

Tracing → Tempo, logs → Loki (log to stdout/stderr, preferably JSON via Logback), metrics → Prometheus. Expose the Micrometer/Prometheus registry on `prometheus.path`.

## Pod lifecycle and graceful shutdown

NAIS injects `preStop` with `sleep 5` before `SIGTERM`, and the load balancer stops routing traffic before the signal is sent. Readiness probes are **not** part of shutdown — manual readiness toggling in application code is an anti-pattern. Use Ktor's ordinary shutdown (`ApplicationStopping`/`ApplicationStopped`) to drain and cleanly close the connection pool/Kafka consumer. Details and anti-patterns: see [`references/pod-lifecycle.md`](references/pod-lifecycle.md).

## Naisjob — batch jobs

Use `Naisjob` when the team needs batch runs (nightly jobs, one-off migrations, reports): it runs to completion instead of continuously, with no incoming HTTP. The same blocks for `resources`, `accessPolicy`, `gcp`, `kafka` and `azure` as `Application`, plus `schedule` (cron), `activeDeadlineSeconds` and `backoffLimit`. Full template with Kafka and Azure AD: [`references/naisjob-example.md`](references/naisjob-example.md).

## Link to the phase loop

- Put the task scope in the issue/plan and the maintained platform detail in the relevant
  topic document. When a platform choice passes the ADR gate — for example a
  hard-to-reverse and non-obvious choice of ingress, scaling strategy,
  Postgres tier/HA or Kafka pool — recommend the documented track and wait for the
  user's choice before `/domain-modeling` records it.
- A change in `accessPolicy`, auth flags or scopes → run `/security-review` and
  return the evidence to the active task.
- A change in prod resources, replicas or new GCP resources (cost) follows
  the canonical R3/R4 gate in `.github/copilot-instructions.md`.

NAIS docs: https://doc.nais.io/ · Golden Path: https://sikkerhet.nav.no/docs/goldenpath/

## Boundaries

### Always
- Include liveness, readiness and metrics endpoints that actually exist in the Ktor routing.
- Set `resources.limits.memory` (prevents an OOM kill of the node).
- Define an explicit `accessPolicy` (inbound + outbound).
- Use environment-specific manifests (`*-dev.yaml`, `*-prod.yaml`) when the repository does.

### Ask first
- A change in prod resources, replicas or `cpuThresholdPercentage`.
- New GCP resources (cost).
- A change to `accessPolicy` in prod.
- New ingress domains.

### Never
- Set `resources.limits.cpu` (CFS throttling hits the JVM hard).
- Remove `resources.limits.memory`.
- Store secrets in Git.
- Skip health endpoints.
- Use the default HikariCP `maximumPoolSize: 10` in a container.
- Lower `terminationGracePeriodSeconds` below the default 30s.
