---
description: "Covers Postgres, Kafka, auth, ingress, and observability blocks in NAIS manifests. Read when changing one of these platform integrations."
---

# Platform configuration

## PostgreSQL (GCP SQL)

```yaml
gcp:
  sqlInstances:
    - type: POSTGRES_17          # Check the repository's version
      tier: db-f1-micro          # development; production: db-custom-1-3840
      highAvailability: false    # production: true
      diskAutoresize: false      # production: true
      databases:
        - name: budstikka-db
          envVarPrefix: DB
```

Provides `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, and `DB_PASSWORD`.
Run schema changes with Flyway (see `/flyway-migration`). For long-running
migrations, the startup probe must let Flyway finish before liveness restarts the pod.

## Kafka

```yaml
kafka:
  pool: nav-dev                  # or nav-prod
```

Topic names are `{team}.{domain}.v{version}`. Development normally uses
`partitions: 1, replication: 1`; production uses `partitions: 6+, replication: 3`.
A continuous queue consumer belongs in `Application`, not `Naisjob`.

## Azure AD and TokenX

```yaml
azure:
  application:
    enabled: true
    tenant: nav.no               # or trygdeetaten.no

tokenx:
  enabled: true                  # on-behalf-of for a signed-in user
```

- User or person context inbound and downstream: TokenX (OBO).
- Machine-to-machine without person context: Azure AD client credentials, or
  Maskinporten for external organisations.
- A partner acting for an organisation in Altinn: Altinn 3 system user
  (Maskinporten plus a system-user token).

See `/auth-overview` for token validation and audience format; do not duplicate
auth details here.

## Ingress

| Domain | Use |
|---|---|
| `*.nav.no` | Public user-facing surfaces |
| `*.intern.nav.no` | Internal employee surfaces (Nav network/naisdevice) |
| `*.ekstern.nav.no` | External user-facing surfaces outside nav.no |

Development variants are `*.dev.nav.no`, `*.intern.dev.nav.no`, and
`*.ekstern.dev.nav.no`. An API called only by other Nav applications often needs
no ingress; use `accessPolicy.inbound` instead.

```yaml
ingresses:
  - https://syfo-budstikka.intern.dev.nav.no
```

## Observability

```yaml
observability:
  autoInstrumentation:
    enabled: true
    runtime: java
```

Tracing goes to Tempo, logs to Loki (stdout/stderr, preferably JSON through
Logback), and metrics to Prometheus. Expose the Micrometer/Prometheus registry
at `prometheus.path`.
