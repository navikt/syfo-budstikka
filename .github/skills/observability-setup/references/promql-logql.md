---
description: "Slå opp ved skriving av PromQL- og LogQL-queries for syfo-budstikka: throughput, error rate, latency-percentiler, pod restarts, Kafka, og trace-korrelasjon i Loki."
---

# PromQL, LogQL og dashboards

Vanlige queries for Grafana, Prometheus og Loki i syfo-budstikka. Tilpass metric-navn, labels og tidsvinduer til det du faktisk eksponerer.

## PromQL

### Throughput

```promql
sum(rate(ktor_http_server_requests_seconds_count{app="syfo-budstikka"}[5m]))
```

For hendelsesdrevne flyter:

```promql
sum(rate(oppgaver_behandlet_total{app="syfo-budstikka"}[5m])) by (event_type)
```

### Error rate

```promql
sum(rate(ktor_http_server_requests_seconds_count{app="syfo-budstikka",status=~"5.."}[5m]))
/
sum(rate(ktor_http_server_requests_seconds_count{app="syfo-budstikka"}[5m]))
```

Med egne domene-countere:

```promql
sum(rate(oppgaver_behandlet_total{app="syfo-budstikka",result="failure"}[5m]))
/
sum(rate(oppgaver_behandlet_total{app="syfo-budstikka"}[5m]))
```

### Latency-percentiler

```promql
histogram_quantile(
  0.95,
  sum(rate(ktor_http_server_requests_seconds_bucket{app="syfo-budstikka"}[5m])) by (le, route, method)
)
```

For en egendefinert timer:

```promql
histogram_quantile(
  0.99,
  sum(rate(oppgave_behandlingstid_seconds_bucket{app="syfo-budstikka"}[5m])) by (le)
)
```

### Pod restarts og køstørrelse

```promql
sum(increase(kube_pod_container_status_restarts_total{app="syfo-budstikka"}[15m]))
```

```promql
max_over_time(oppgave_ko_storrelse{app="syfo-budstikka"}[10m])
```

## LogQL

### Filtrering

```logql
{app="syfo-budstikka", namespace="team-esyfo"} |= "ERROR"
```

```logql
{app="syfo-budstikka", namespace="team-esyfo"} | json | level="error"
```

### Aggregering

Feil per container per minutt:

```logql
sum(rate({app="syfo-budstikka", namespace="team-esyfo"} |= "ERROR" [1m])) by (container)
```

Strukturerte logger gruppert på event-type:

```logql
sum by (event_type) (
  rate({app="syfo-budstikka"} | json | event_type=~".+" [5m])
)
```

### Korrelasjon med traces

Hent alle logger for et trace (klikkbar fra Tempo i Grafana):

```logql
{app="syfo-budstikka", namespace="team-esyfo"}
| json
| trace_id="2f2f2264a8b6df9f8b3d614f4c9ce111"
```

Kombiner med feilnivå:

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

## Praktiske tips

- Bruk samme label-sett i dashboard og varsler der det gir mening
- Normaliser `route` før du bygger paneler — ekspanderte path-parametre gir støyete grafer og høy kardinalitet
- Se alltid på både metrics og logs når du feilsøker latency eller feilrater
- Bruk traces når du må finne flaskehalser på tvers av HTTP, Kafka og Postgres
