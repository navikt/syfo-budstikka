---
name: observability-setup
description: "Establish or improve observability in syfo-budstikka. Use when work touches Micrometer metrics, health and scrape routes, structured logging and MDC, PromQL or LogQL, dashboards, tracing, or alerts."
---

# Observability in syfo-budstikka

Start from working repository signals before adding anything:

- `infrastructure/metrics/MetricsPlugin.kt` installs `MicrometerMetrics`.
- `MicrometerDispatchMetrics` implements the `DispatchMetrics` application port.
- `BackgroundLoop` has run, failure, and duration metrics.
- `api/InternalApi.kt` exposes health and scrape endpoints.
- `logback.xml` writes structured JSON.
- Worker, consumer, event, and delivery flows use focused MDC fields.

## Workflow

1. Read `nais/nais-*.yaml`, `application.conf`, `logback.xml`, and relevant
   metrics/logging classes.
2. Name the operational question a signal must answer. Find an existing metric,
   log, or trace before proposing a new one.
3. Select the lowest-cost signal that answers it: metric for frequency/duration,
   structured log for explanation, trace for a chain.
4. Implement through existing ports and Ktor `DependencyRegistry`.
5. Verify scrape/logs locally and query/alert in development before production.

## Current Ktor/NAIS contract

Paths must match in `InternalApi.kt` and `nais/nais-*.yaml`:

- liveness: `/internal/health/is_alive` — consumer heartbeat
- readiness: `/internal/health/is_ready` — database health
- Prometheus: `/internal/metrics`

The repository shares one `PrometheusMeterRegistry` from `DependencyRegistry`.
`MicrometerMetrics`, application metrics, workers, and Kafka clients use the
same registry. Read [references/micrometer.md](references/micrometer.md) for
the repository extension pattern.

## Metric contract

- Name metrics in existing Micrometer dot notation; Prometheus exposes snake_case
  and `_total` for counters.
- Use stable, bounded labels such as `channel`, `result`, `operation`, `worker`, and `handler`.
- Identifiers, raw URLs, event IDs, references, personal identifiers, and trace IDs are not labels.
- Add domain metrics behind an application port when the domain must count; keep Micrometer in infrastructure.
- Every new label needs a concrete query, panel, or alert that consumes it.

## Logging and correlation

HTTP uses `Nav-Call-Id` through Ktor `CallId`. Long-running worker and Kafka
flows correlate through existing MDC keys including `worker`, `consumer`,
`eventId`, `reference`, `channel`, and `handler`. Preserve them across coroutine
suspension with `MDCContext`, as existing code does.

The repository has no `CallLogging` or explicit OpenTelemetry wiring in
application code today. Do not claim `trace_id`/`span_id` before NAIS
configuration and actual logs are verified. Read
[references/logging-correlation.md](references/logging-correlation.md) when
introducing request logging or tracing.

Logs never contain raw payload, personal identifier, token, or secret. Use
structured fields and low-cardinality metric labels for distinct purposes.

## Dashboards and alerts

Start with golden signals, worker failure/duration, Kafka, and delivery results.
Use NAIS labels `app`, `namespace`, and `cluster` in queries; do not duplicate
them in custom metrics. Read [references/promql-logql.md](references/promql-logql.md)
for query patterns.

Alert on observed symptoms with a runbook and owner. Grill new thresholds,
alert channels, and labels with meaningful cardinality risk; record as an ADR
when appropriate. Read [references/alerting.md](references/alerting.md) when
adding Prometheus rules or Nais Alert routing.

## Completion criteria

- Code, NAIS paths, and documentation are aligned.
- Targeted tests and `./gradlew build` have fresh evidence.
- New metrics can be scraped and the consuming query has been tried.
- New logs are verified without PII and with expected structured fields.
- Describe tracing as active only when the development environment shows a real trace.
