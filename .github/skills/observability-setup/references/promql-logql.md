---
description: "Look this up when writing PromQL and LogQL queries for this service: throughput, error rate, latency percentiles, pod restarts, Kafka, and trace correlation in Loki."
---

# PromQL, LogQL and dashboards

Common queries for Grafana, Prometheus and Loki in this repository. Adapt metric names, labels and time windows to what you actually expose.

## PromQL

### Throughput

```promql
sum(rate(ktor_http_server_requests_seconds_count{app="syfo-budstikka"}[5m]))
```

For event-driven flows:

```promql
sum(rate(oppgaver_behandlet_total{app="syfo-budstikka"}[5m])) by (event_type)
```

### Error rate

```promql
sum(rate(ktor_http_server_requests_seconds_count{app="syfo-budstikka",status=~"5.."}[5m]))
/
sum(rate(ktor_http_server_requests_seconds_count{app="syfo-budstikka"}[5m]))
```

With your own domain counters:

```promql
sum(rate(oppgaver_behandlet_total{app="syfo-budstikka",result="failure"}[5m]))
/
sum(rate(oppgaver_behandlet_total{app="syfo-budstikka"}[5m]))
```

### Latency percentiles

```promql
histogram_quantile(
  0.95,
  sum(rate(ktor_http_server_requests_seconds_bucket{app="syfo-budstikka"}[5m])) by (le, route, method)
)
```

For a custom timer:

```promql
histogram_quantile(
  0.99,
  sum(rate(oppgave_behandlingstid_seconds_bucket{app="syfo-budstikka"}[5m])) by (le)
)
```

### Pod restarts and queue size

```promql
sum(increase(kube_pod_container_status_restarts_total{app="syfo-budstikka"}[15m]))
```

```promql
max_over_time(oppgave_ko_storrelse{app="syfo-budstikka"}[10m])
```

## LogQL

### Filtering

```logql
{app="syfo-budstikka", namespace="team-esyfo"} |= "ERROR"
```

```logql
{app="syfo-budstikka", namespace="team-esyfo"} | json | level="error"
```

### Aggregation

Errors per container per minute:

```logql
sum(rate({app="syfo-budstikka", namespace="team-esyfo"} |= "ERROR" [1m])) by (container)
```

Structured logs grouped by event type:

```logql
sum by (event_type) (
  rate({app="syfo-budstikka"} | json | event_type=~".+" [5m])
)
```

### Correlation with traces

Fetch all logs for a trace (clickable from Tempo in Grafana):

```logql
{app="syfo-budstikka", namespace="team-esyfo"}
| json
| trace_id="2f2f2264a8b6df9f8b3d614f4c9ce111"
```

Combine with error level:

```logql
{app="syfo-budstikka"}
| json
| level="error"
| trace_id=~".+"
```

### Kafka

```logql
{app="syfo-budstikka"}
| json
| event_type="oppgave_opprettet"
| result="failure"
```

```logql
{app="syfo-budstikka"} |= "consumer lag"
```

## Practical tips

- Use the same label set in dashboards and alerts where it makes sense
- Normalise `route` before you build panels — expanded path parameters give noisy graphs and high cardinality
- Always look at both metrics and logs when debugging latency or error rates
- Use traces when you need to find bottlenecks across HTTP, Kafka and Postgres
