---
description: "Look this up when writing Micrometer metrics in Ktor: MeterRegistry setup, Counter/Timer/Gauge/DistributionSummary, domain and Kafka metrics, and health routes in this repository."
---

# Micrometer and health in Ktor

Backend patterns for Kotlin/Ktor in this repository. Ktor has no Actuator — the registry setup is owned here, and it already exists: read it before adding anything.

## MeterRegistry setup

A single `PrometheusMeterRegistry` is provided through Ktor's dependency injection plugin —
`dependencies { provide { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) } }` in
`src/main/kotlin/no/nav/budstikka/bootstrap/DependencyInjection.kt` — and consumed with
`val registry: PrometheusMeterRegistry by dependencies` wherever code measures. There is no
Koin in this repository.

`installMetrics()` in `src/main/kotlin/no/nav/budstikka/infrastructure/metrics/MetricsPlugin.kt`
installs the `MicrometerMetrics` plugin against that shared registry, with the JVM and process
binders. The scrape endpoint is `GET /internal/metrics` — `registry.scrape()` in
`src/main/kotlin/no/nav/budstikka/api/InternalApi.kt` — matching `prometheus.path` in the NAIS
manifest.

`MicrometerMetrics` automatically provides `ktor_http_server_requests_seconds` with tags for
route, method and status. For p95/p99 in Prometheus a timer must publish histogram buckets
(`percentilesHistogram(true)` in `distributionStatisticConfig`, or `publishPercentileHistogram()`
on the builder) — without it the `_bucket` series does not exist and `histogram_quantile`
returns nothing.

## Domain and Kafka metrics

Use the standard Micrometer builders; the house style lives in
`src/main/kotlin/no/nav/budstikka/infrastructure/metrics/MicrometerDispatchMetrics.kt` and
`src/main/kotlin/no/nav/budstikka/infrastructure/worker/BackgroundLoop.kt`: dot-form meter names
(`inbox.message.processed` becomes `inbox_message_processed_total` when scraped), name and label
constants kept in one companion object, and low-cardinality PII-free labels. For Kafka and
domain metrics, label with values like `event_type`, `result`, `channel`, `reason`, `topic` or
`consumer_group` — never message key, payload id, `fnr` or aktør-id.

## Health routes

The routes live in `src/main/kotlin/no/nav/budstikka/api/InternalApi.kt`:
`GET /internal/health/is_alive` and `GET /internal/health/is_ready`, matching the probe paths in
the NAIS manifests (`nais/nais-dev.yaml`, `nais/nais-prod.yaml`).

- `is_ready` (readiness) answers whether the instance can take traffic right now. Here it checks
  the database only (`src/main/kotlin/no/nav/budstikka/infrastructure/database/config/HealthCheck.kt`).
  The Kafka consumer must NOT be in readiness: the consumer serves no HTTP traffic, so a dead
  consumer should not pull the pod out of load balancing (see `docs/helsesjekk.md`).
- `is_alive` (liveness) is where consumer health belongs, via the self-reported heartbeat
  contract: each consumer runner and background loop updates a heartbeat every poll cycle, and
  the aggregated `LivenessCheck` (`src/main/kotlin/no/nav/budstikka/bootstrap/Liveness.kt`)
  reports stale when any loop stops cycling, so the platform restarts the pod. Never tie
  liveness to broker availability or consumer lag (`docs/helsesjekk.md`).
- Do not put heavy logic in health checks, and keep responses free of sensitive information.
- Netty/`EngineMain` handles `SIGTERM` and graceful shutdown — you do not need to flip readiness
  manually at shutdown.
