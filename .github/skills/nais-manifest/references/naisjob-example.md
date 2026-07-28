---
description: "Provides a complete Naisjob template for a cron batch with Kafka, Azure AD M2M, and Postgres, plus selection guidance. Read when creating or changing a batch or cron job in no.nav.budstikka."
---

# Naisjob — full example

Batch job for an application team with Kafka producer, Azure AD through M2M
client credentials, and Postgres.

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
  # Cron: runs at 02:00 every night. Omit for a one-off run.
  schedule: "0 2 * * *"
  # Guardrails against hanging jobs and failure loops
  activeDeadlineSeconds: 3600   # Abort after 1 hour
  backoffLimit: 2               # At most 2 retries on failure
  ttlSecondsAfterFinished: 86400
  # Observability: same as Application
  prometheus:
    enabled: true
    path: /internal/metrics
  observability:
    autoInstrumentation:
      enabled: true
      runtime: java
  # No CPU limit (CFS throttling); a memory limit is mandatory
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
  # Access control: explicitly list everything the job calls
  accessPolicy:
    outbound:
      rules:
        - application: syfo-budstikka
          namespace: team-esyfo
        - application: pdl-api
          namespace: pdl
      external:
        - host: graph.microsoft.com
  # Kafka: same pool naming as Application
  kafka:
    pool: nav-prod
  # Postgres: dedicated job database or shared with Application
  gcp:
    sqlInstances:
      - type: POSTGRES_17
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
- It runs **on a schedule** (cron) or **manually** (one-off).
- It must **not** expose HTTP for inbound traffic.

For a continuous Kafka queue consumer, use `Application`, not `Naisjob`.

## Scheduling guidance

- Avoid near-every-minute frequency. Use a long-running `Application` if the job
  runs more often than every five minutes.
- Set `concurrencyPolicy: Forbid` when a job could overlap itself.
- Test cron expressions with [crontab.guru](https://crontab.guru) before committing.
