---
description: "Full Naisjob template (cron batch with Kafka, Azure AD M2M and Postgres) and guidance on when Naisjob is the right choice over Application. Read when creating or changing a batch/cron job in this backend."
---

# Naisjob — full example

Batch job for an application team, with a Kafka producer, Azure AD (M2M via client credentials) and Postgres.

```yaml
apiVersion: nais.io/v1
kind: Naisjob
metadata:
  name: nightly-report
  namespace: team-esyfo
  labels:
    team: team-esyfo
spec:
  image: {{ image }}

  # Cron — runs at 02:00 every night. Omit for a pure one-off run.
  schedule: "0 2 * * *"

  # Safety net against hanging jobs and failure loops
  activeDeadlineSeconds: 3600   # Abort after 1 hour
  backoffLimit: 2               # Max 2 retries on failure
  ttlSecondsAfterFinished: 86400

  # Observability — same as Application
  prometheus:
    enabled: true
    path: /internal/metrics
  observability:
    autoInstrumentation:
      enabled: true
      runtime: java

  # No cpu limit (CFS throttling); the memory limit is mandatory
  resources:
    requests:
      cpu: 100m
      memory: 512Mi
    limits:
      memory: 1Gi

  # M2M via Azure AD client credentials
  azure:
    application:
      enabled: true
      tenant: nav.no

  # Access control — be explicit about everything the job calls
  accessPolicy:
    outbound:
      rules:
        - application: syfo-budstikka
          namespace: team-esyfo
        - application: pdl-api
          namespace: pdl
      external:
        - host: graph.microsoft.com

  # Kafka — same pool naming as Application
  kafka:
    pool: nav-prod

  # Postgres — dedicated job database or shared with Application
  gcp:
    sqlInstances:
      - type: POSTGRES_18       # Same version as the Application manifests (nais/nais-dev.yaml, nais/nais-prod.yaml)
        tier: db-custom-1-3840
        highAvailability: true
        diskAutoresize: true
        databases:
          - name: report-db
            envVarPrefix: DB

  # Environment variables for the job itself
  env:
    - name: REPORT_TARGET_BUCKET
      value: gs://team-esyfo-rapporter
```

## When should you use Naisjob rather than Application?

- The job has a **defined end** (report run, migration, cleanup).
- It must run **on a schedule** (cron) or **manually** (one-off run).
- It must **not** expose HTTP for incoming traffic.

If you need a continuous Kafka queue consumer, use `Application` — not `Naisjob`.

## Scheduling tips

- Avoid near-every-minute frequency — use a long-running `Application` instead if the job runs more often than every 5 minutes.
- Set `concurrencyPolicy: Forbid` if a job can overlap itself.
- Test cron expressions with [crontab.guru](https://crontab.guru) before you commit.
