---
name: nais-manifest
description: "Create and change NAIS Application and Naisjob manifests. Use when work touches ingress, resources, probes, accessPolicy, authentication, Kafka, GCP Postgres, or scaling."
---

# NAIS manifests

Create or update a complete NAIS manifest for the continuous service
(`Application`) or a batch job (`Naisjob`). Focus on server port, JVM runtime,
and observability.

## Workflow

1. Read `nais/nais-dev.yaml` and `nais/nais-prod.yaml` first. Reuse the actual
   `namespace`, `labels.team`, health paths, and Prometheus path; never assume values.
2. Map real needs: Postgres, Kafka, TokenX/Azure AD, ingress, scaling, or a
   batch with a defined end. Follow the existing environment-specific pattern.
3. Verify `port`, `prometheus.path`, `liveness.path`, and `readiness.path`
   against Ktor routing before committing. The endpoints must exist in code.
4. Change auth code and `accessPolicy` together, then verify the manifest and tests.

## Non-negotiable contract

- Never set `resources.limits.cpu`; use `requests.cpu`. Always set
  `limits.memory`, with JVM headroom above heap for metaspace, threads, and direct buffers.
- Explicitly define `accessPolicy.inbound` and `accessPolicy.outbound`; specify
  `namespace` and `cluster` for calls across clusters.
- A continuous Kafka consumer is an `Application`, not a `Naisjob`. Secrets
  never belong in Git; NAIS injects them at runtime.
- Do not manually toggle readiness during shutdown. Let Ktor drain and close
  Hikari/Kafka through `ApplicationStopping` or `ApplicationStopped`.
- A change to `accessPolicy`, auth flags, or scopes requires
  `/nav-security-review`.
  Changed production resources, replicas, or new GCP resources require
  `grill-inspektor` evidence in KOKK_RESULT/PR.

## Read as needed

- [Application template and accessPolicy example](references/application-example.md)
- [Resources and JVM/Hikari sizing](references/resources.md)
- [Postgres, Kafka, auth, ingress, and observability](references/platform-configuration.md)
- [Pod lifecycle and graceful shutdown](references/pod-lifecycle.md)
- [Naisjob template and scheduling](references/naisjob-example.md)

## Boundaries

### Always

- Include liveness, readiness, and metrics endpoints that exist in Ktor routing.
- Set `resources.limits.memory`, explicit accessPolicy, and environment-specific
  manifests when the repository already uses them.
- Record durable platform choices such as ingress, scaling, Postgres tier/HA,
  and Kafka pool as an ADR.

### Ask first

- Changed production resources, replicas, `cpuThresholdPercentage`, accessPolicy, or ingress.
- New GCP resources, auth mechanism, or external access.

### Never

- Set a CPU limit, remove a memory limit, or store secrets in Git.
- Skip health endpoints, use HikariCP’s default `maximumPoolSize: 10` in a
  container, or lower `terminationGracePeriodSeconds` below 30 seconds.

NAIS documentation: https://doc.nais.io/ · Golden Path: https://sikkerhet.nav.no/docs/goldenpath/
