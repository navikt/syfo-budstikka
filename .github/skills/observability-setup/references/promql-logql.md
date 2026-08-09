---
description: "Look this up when writing PromQL and LogQL queries for this service: throughput, error rate, latency percentiles, pod restarts, Kafka, and trace correlation in Loki."
---

# PromQL, LogQL and dashboards

Queries for Grafana, Prometheus/Mimir and Loki in this repository. The names below are the
metrics this app actually exports — the same names the checked-in dashboard queries
(`grafana/dashboards/syfo-budstikka.json`). Start from those; do not invent names.

## The app's metric names

- `ktor_http_server_requests_seconds` — the Ktor HTTP server metric from `MicrometerMetrics`
  (tags: route, method, status). Note the `ktor_` prefix — this is not Spring's
  `http_server_requests_seconds`. Only the internal probes generate HTTP traffic here.
- `inbox_message_claimed_total`, `inbox_message_empty_polls_total`,
  `inbox_message_processed_total`, `inbox_message_dropped_total{reason}`,
  `inbox_message_failed_total` — inbox pipeline
  (`src/main/kotlin/no/nav/budstikka/infrastructure/metrics/MicrometerDispatchMetrics.kt`)
- `delivery_total{channel,result}`, `delivery_claimed_total`, `delivery_empty_polls_total` —
  delivery pipeline (same file)
- `worker_runs_total{worker}`, `worker_failures_total{worker}`, `worker_duration_seconds_*` —
  background loops (`src/main/kotlin/no/nav/budstikka/infrastructure/worker/BackgroundLoop.kt`)
- `kafka_consumer_fetch_manager_records_lag_max{topic,partition}` and
  `kafka_consumer_fetch_manager_records_consumed_total` — the Kafka client metrics; this is the
  consumer-lag metric here, not `kafka_consumer_lag` / `kafka_consumergroup_lag`

Example queries, straight from the dashboard:

```promql
sum(rate(inbox_message_processed_total[5m]))
sum by (reason)(rate(inbox_message_dropped_total[5m]))
sum by (channel)(rate(delivery_total{result="sent"}[5m]))
sum by (worker)(rate(worker_failures_total[5m]))
sum by (partition)(kafka_consumer_fetch_manager_records_lag_max{topic="team-esyfo.budstikka.v1"})
```

The standard PromQL toolbox (`rate`, `increase`, `histogram_quantile`, error ratios) is not
repeated here — the one repo-specific gotcha is that `histogram_quantile` needs `_bucket`
series, which exist only for timers that publish percentile histograms (see
`references/micrometer.md`).

## LogQL

The app logs one JSON line per entry via `LogstashEncoder`
(`src/main/resources/logback.xml`), so parse with `| json`. Loki's own labels (`app`,
`namespace`, `cluster`, `container`, `pod`) come from the platform — filter on those first,
then parse.

Queries the dashboard uses:

```logql
{app="syfo-budstikka"} | json | level="error"
sum(rate({app="syfo-budstikka"} | json | level="error" [5m]))
{app="syfo-budstikka"} | json | event_id="$traceId" or reference="$traceId"
```

The message-trace pattern works because workers and handlers put `event_id` and `reference` on
the logs as structured fields (`src/main/kotlin/no/nav/budstikka/application/MdcKeys.kt` +
`StructuredArguments.kv`). Dead-lettered messages can lack `event_id`; they log
"Poison inbox message dead-lettered" with Kafka coordinates instead
(`infrastructure/kafka/consumer/InboxMessageHandler.kt`).

## Practical tips

- Use the same label set in dashboards and alerts where it makes sense
- Always look at both metrics and logs when debugging pipeline failures
- Use traces when you need to find bottlenecks across HTTP, Kafka and Postgres
