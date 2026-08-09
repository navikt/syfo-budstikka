---
name: observability-setup
description: "Use when establishing or improving observability in this repository: Micrometer metrics and PrometheusMeterRegistry in Ktor, the MicrometerMetrics plugin, /internal/health/is_alive|is_ready and /internal/metrics, structured JSON logging with trace_id/callId, correlation ID (Nav-Call-Id/x_request_id), OpenTelemetry tracing, PromQL/LogQL, Grafana dashboards and Prometheus alerts in NAIS — or when someone says /observability-setup."
---

# Observability in this repository

Ktor 3.x on Netty, package `no.nav.budstikka`, running in NAIS. Keep the main rules here short — use `references/` for full examples.

- **Metrics** tell you *what* is happening
- **Logs** explain *why* it happened
- **Traces** show *where* in the flow it happened
- Always verify the existing setup in the repository before adding new measurement points, labels or alerts

## Workflow

1. Read the NAIS manifest, `src/main/resources/application.conf`, `logback.xml` and `build.gradle.kts`/`gradle/libs.versions.toml` for existing observability setup.
2. Find the established patterns for `MicrometerMetrics`, `MeterRegistry` injection (Ktor DI, `dependencies { }`), `CallId`, MDC fields and health routes.
3. Verify which endpoints NAIS actually scrapes and probes: `/internal/health/is_alive`, `/internal/health/is_ready`, `/internal/metrics`. The paths in the code must match the manifest.
4. Start with standard metrics (Ktor HTTP server + JVM) and extend with domain metrics that provide operational value.
5. Add dashboards and alerts only once the metrics and the label set are stable.

## Metrics in Ktor (Micrometer)

Ktor has no Actuator: the registry, the `MicrometerMetrics` plugin and the internal
routes are set up explicitly. The repository's actual setup in `infrastructure/metrics/` and
`bootstrap/` is the source of truth — read that rather than an example here.

One choice that is not obvious:

- `liveness` (`/internal/health/is_alive`) must only answer whether the process ought to be restarted.
  `readiness` (`/internal/health/is_ready`) must depend on actual dependencies, but be
  kept lightweight. Consumer lag belongs in alerting, never in liveness
  (see [helsesjekk](../../../docs/helsesjekk.md)).

See `references/micrometer.md` for the registry wiring (Ktor DI), the percentile-histogram gotcha, domain and Kafka metrics, and the health-route contract.

## Naming for metrics and labels

The Prometheus naming conventions (`snake_case`, `_total`, `_seconds`) are standard and
apply as they do elsewhere. What is NAIS-specific is the labels:

### NAIS label conventions

NAIS automatically adds a set of labels. Do not duplicate these on your own metrics — use them in queries, dashboards and alerts:

- `app` — application name from the NAIS manifest (`syfo-budstikka`)
- `team` / `namespace` — ownership and Kubernetes namespace, used for alert routing
- `cluster` — `dev-gcp` / `prod-gcp`

Your own labels must cover domain aspects:
- Good: `method`, `route`, `status`, `event_type`, `result`, `consumer_group`, `topic`
- Bad (high cardinality / PII): `user_id`, `fnr`, `aktor_id`, `trace_id`, `callId`, raw URLs with dynamic segments
- Prefer normalised route values (`/api/oppgaver/{id}`), not expanded path parameters
- Every unique label combination is a new time series: only add labels that are actually used in dashboards, alerts or debugging

## Correlation ID in the NAV stack

A correlation ID lets you follow a request across services, Kafka messages and logs. The repository installs `CallId` on the internal probes only; `CallLogging` and `callIdMdc` are not installed. Worker and consumer correlation goes through `MdcKeys` + `MDCContext` + `StructuredArguments.kv` — build on that, do not set up something in parallel.

### Headers
- `Nav-Call-Id` — NAV convention; read it and propagate it on all outgoing HTTP calls and Kafka headers
- `X-Request-Id` / `X-Correlation-ID` — accept as a fallback for external integrations
- W3C `traceparent` — set automatically by the OpenTelemetry agent in NAIS

The header name is exact: `Nav-Call-Id`, not `Nav-Callid`. HTTP header names are
case-insensitive but not hyphen-insensitive, so the two are different headers and
a mismatch silently breaks correlation. The constant lives in
`src/main/kotlin/no/nav/budstikka/api/Plugins.kt`:

```kotlin
const val NAV_CALL_ID_HEADER = "Nav-Call-Id"

install(CallId) {
    retrieve { it.request.headers[NAV_CALL_ID_HEADER] }
    generate { UUID.randomUUID().toString() }
    verify { callId: String -> callId.isNotEmpty() }
    header(NAV_CALL_ID_HEADER)
}
```

### MDC and trace correlation
Put `callId` and `trace_id` on the MDC so the logback encoder automatically gets them on all logs in request scope. With the OpenTelemetry agent you can fetch the active trace:

```kotlin
MDC.put("callId", call.callId)
MDC.put("trace_id", Span.current().spanContext.traceId)
```

Include `trace_id`, `span_id` and `callId` in the logs so Loki can correlate with Tempo (clickable trace IDs in Grafana).

## Logging and tracing

- Log structured JSON to stdout — NAIS Loki picks it up automatically. Do not write to file.
- Use `logstash-logback-encoder` with `LogstashEncoder`/`net.logstash.logback.encoder` in `logback.xml`; add domain data as structured fields via `StructuredArguments.kv(...)`, not through string interpolation.
- Do not use logging as a substitute for metrics — metrics answer frequency, volume and duration.
- Use tracing for request chains, Kafka flows and calls to Postgres or external services. Enable OpenTelemetry auto-instrumentation in NAIS before adding manual spans.

### JSON format for NAIS Loki

One JSON line per log entry on stdout. Fields Loki parses and indexes:

```json
{
  "@timestamp": "2026-06-29T10:23:45.123Z",
  "level": "INFO",
  "message": "Delivery sent",
  "logger_name": "no.nav.budstikka.application.DeliveryWorker",
  "thread_name": "eventLoopGroupProxy-4-1",
  "trace_id": "2f2f2264a8b6df9f8b3d614f4c9ce111",
  "span_id": "b3d614f4c9ce111a",
  "callId": "abc-123",
  "event_type": "delivery_sent"
}
```

Minimum fields: `@timestamp`, `level`, `message`. Put domain data in top-level fields (not nested under `context`). Automatic Loki labels (`app`, `namespace`, `cluster`, `container`, `pod`, `stream`) must not be duplicated in the payload. Never national identity numbers, aktør-id, tokens or other special categories of personal data in the log.

## Grafana dashboards for the service

The dashboard already exists: `grafana/dashboards/syfo-budstikka.json`. It is built on the
domain metrics the app actually exports — `inbox_message_*` (claimed, processed, dropped,
failed, empty polls), `delivery_total{channel,result}`, `worker_runs_total` /
`worker_failures_total` / `worker_duration_seconds`, Kafka consumer lag, and Loki panels over
`{app="syfo-budstikka"} | json`. Start from that file when adding or changing panels; do not
design a new baseline from scratch.

- **Consumer lag** is `kafka_consumer_fetch_manager_records_lag_max` per `topic`/`partition` —
  the metric the app exports and the dashboard queries
  (`grafana/dashboards/syfo-budstikka.json`) — not `kafka_consumer_lag` or
  `kafka_consumergroup_lag`.
- HTTP golden signals (request rate, error rate, latency) say little here: the app's only HTTP
  traffic is the internal probes (`api/InternalApi.kt`). The pipeline panels are this app's
  golden signals.
- Tie new panels to metrics the app exports today (`infrastructure/metrics/`,
  `infrastructure/worker/BackgroundLoop.kt`), with `app`, `namespace` and `cluster` as template
  variables where useful.

See `references/promql-logql.md` for the app's metric names and query examples.

## Alerting

- No alert configuration exists in this repository today. When introducing alerts, start from
  the candidates in `references/alerting.md` — consumer lag, inbox/delivery/worker failures,
  pod restarts from a stale heartbeat — not HTTP error rate/latency, which here only measure
  the probes.
- Use clear annotations; distinguish between `warning` and `critical`
- Keep thresholds cautious until you know the traffic patterns — test in `dev-gcp` before tightening them in prod

See `references/alerting.md` for the alert candidates and the current NAIS mechanism (`PrometheusRule`).

## Decision candidates

Grill non-routine choices with `/grilling`: labels that could increase
cardinality substantially, production thresholds, alerting channels that affect the
team's workflow, and storage of sensitive domain data. Put
observability detail that follows from the approved change into the relevant
topic document. When a lasting choice passes the ADR gate, recommend the documented
route and wait for the user's choice before `/domain-modeling` records it.

## Checklist

- [ ] `/internal/health/is_alive`, `/internal/health/is_ready` and the scrape path (`/internal/metrics`) match the NAIS manifest
- [ ] `MicrometerMetrics` installed with a shared `PrometheusMeterRegistry` + JVM binders
- [ ] OpenTelemetry auto-instrumentation considered/enabled in NAIS
- [ ] Structured JSON logging to stdout with `trace_id`, `span_id`, `callId`
- [ ] `Nav-Call-Id` is read via `CallId`, propagated on outgoing calls and put on the MDC
- [ ] Key domain metrics defined with stable `snake_case` names and low-cardinality labels
- [ ] Dashboard changes start from `grafana/dashboards/syfo-budstikka.json` and use metrics the app exports (inbox, delivery, workers, consumer lag, error logs)
- [ ] Alert candidates (none configured in the repo yet) follow `references/alerting.md`: consumer lag, inbox/delivery/worker failures, pod restarts
- [ ] Logs, traces and metric labels do not contain national identity numbers, aktør-id, tokens or other secrets

## Boundaries

### Always
- Use `snake_case` and unit suffixes for metrics
- Use low and bounded label values
- Log structured JSON to stdout (not files)
- Propagate `Nav-Call-Id` explicitly on outbound calls; worker/consumer log correlation goes through `MdcKeys` + `MDCContext` (`CallLogging`/`callIdMdc` are not installed)
- Follow the existing logging and metrics patterns in the repository
- Verify health paths, scrape path and tracing setup against the actual NAIS config and `application.conf`

### Ask first / grill
- New labels that could increase cardinality substantially
- Changing production thresholds for alerts
- New dashboards, folders or alerting channels

### Never
- Log or expose national identity numbers, aktør-id, tokens, passwords or other special categories of personal data
- Use `camelCase` in metric names
- Use labels with high cardinality (`user_id`, `fnr`, `trace_id`, `callId`)
- Add observability code that cannot be explained operationally or used in practice
