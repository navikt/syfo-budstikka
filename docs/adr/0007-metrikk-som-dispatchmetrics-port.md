# ADR 0007 — Metrics setup: `DispatchMetrics` port and Micrometer binders

- Status: Decided (issue #28 metrics-only slice, #41 consumer-lag metric)
- Date: 2026-07-14
- Related: B45–B49, B57, ADR 0003, ADR 0004, issues #28 and #41

## Context

Existing Prometheus registry, `/internal/metrics`, JSON logging, and `CallId`
lacked HTTP/JVM metrics, Kafka client lag metrics, and inbox-to-delivery metrics.
`domain`/`application` cannot depend on infrastructure/Micrometer. Tracing,
expanded logging, dashboards, and NAIS alerts are deferred.

## Decision

1. Infrastructure installs `MicrometerMetrics` with JVM/process binders and
   `KafkaClientMetrics` per consumer, including
   `kafka_consumer_fetch_manager_records_lag_max`. Close wiring before transient
   consumer rebind to avoid dead `client.id` time series.
2. `BackgroundLoop` emits generic `worker_runs_total`,
   `worker_duration_seconds`, and `worker_failures_total{worker}`; nullable
   registry is no-op for unit tests.
3. Application emits inbox/delivery events through `DispatchMetrics`; bootstrap
   wires `MicrometerDispatchMetrics` on the shared registry. Workers stay free of
   Micrometer imports and test with a recording fake.

Metrics use English Prometheus `snake_case`, `_total`/`_seconds`, with these
literal names from B57:

- `inbox_message_claimed_total`
- `inbox_message_empty_polls_total`
- `inbox_message_processed_total`
- `inbox_message_dropped_total{reason}`
- `inbox_message_failed_total`
- `delivery_claimed_total`
- `delivery_empty_polls_total`
- `delivery_total{channel,result}`

Labels are low-cardinality and PII-free: never `eventId`, FNR, or personal
data.

## Consequences

Consumer lag is exposed at `/internal/metrics`; a `PrometheusRule` alert remains
for the future manifest. Lease races may count an outcome on a losing replica;
this is accepted observability noise, not accounting. A channel adds a
`delivery_total{channel}` value; a domain metric adds one port method/adapter line.
