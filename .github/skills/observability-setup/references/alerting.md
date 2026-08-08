---
description: "Look this up when writing Prometheus alerts and NAIS Alert resources for this service: error rate, latency, pod restarts, Kafka lag and Slack routing."
---

# Alerting and notifications in NAIS

Practical patterns for Prometheus rules and notifications to Slack via NAIS. Prioritise alerts that point at real user or operational problems the team actually has to react to.

## Common alerting patterns

### High error rate

```yaml
groups:
  - name: syfo-budstikka-alerts
    rules:
      - alert: HighErrorRate
        expr: |
          (
            sum(rate(ktor_http_server_requests_seconds_count{app="syfo-budstikka",status=~"5.."}[5m]))
            /
            sum(rate(ktor_http_server_requests_seconds_count{app="syfo-budstikka"}[5m]))
          ) > 0.05
        for: 10m
        labels:
          severity: critical
        annotations:
          summary: "Høy feilrate for syfo-budstikka"
          description: "Mer enn 5% av forespørslene feiler med 5xx over 10 minutter"
          runbook_url: "https://teamdocs/runbooks/syfo-budstikka-errors"
```

### Latency spike

```yaml
- alert: HighLatencyP95
  expr: |
    histogram_quantile(
      0.95,
      sum(rate(ktor_http_server_requests_seconds_bucket{app="syfo-budstikka"}[5m])) by (le)
    ) > 1
  for: 15m
  labels:
    severity: warning
  annotations:
    summary: "Høy latency for syfo-budstikka"
    description: "p95-latency er over 1 sekund"
```

### Pod restart / unavailability

```yaml
- alert: PodRestarts
  expr: sum(increase(kube_pod_container_status_restarts_total{app="syfo-budstikka"}[15m])) > 3
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Pods restarter hyppig"
    description: "syfo-budstikka har restartet mer enn 3 ganger på 15 minutter"

- alert: ApplicationDown
  expr: sum(up{app="syfo-budstikka"}) == 0
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "Applikasjonen er nede"
    description: "Ingen friske targets scrapes for syfo-budstikka"
```

### Kafka / queue problems

```yaml
- alert: KafkaConsumerLagHigh
  expr: max(kafka_consumer_lag{app="syfo-budstikka"}) > 10000
  for: 15m
  labels:
    severity: warning
  annotations:
    summary: "Høy Kafka consumer lag"
    description: "Lag har holdt seg over 10000 i 15 minutter"
```

## NAIS patterns for alerting rules

- Use short, stable alert names
- Always add `summary`, `description` and preferably a runbook link
- `warning` for things that should be looked into, `critical` for an active incident
- Alert on symptoms before internal indicators
- Test thresholds in `dev-gcp` before tightening them in prod
- Avoid many near-identical alerts with small variations in threshold
- Grill changes to production thresholds with `/grilling`. When the choice passes
  the ADR gate, recommend the documented route and wait for the user's choice before
  `/domain-modeling` records it.

## Slack routing via NAIS Alert

```yaml
apiVersion: nais.io/v1
kind: Alert
metadata:
  name: syfo-budstikka-alerts
  namespace: team-esyfo
spec:
  receivers:
    slack:
      channel: "#team-esyfo-alerts"
      prependText: "@here "
  alerts:
    - alert: HighErrorRate
    - alert: HighLatencyP95
    - alert: ApplicationDown
```

Choose the channel and `prependText` with care. Critical alerts may use `@here`; noisy alerts normally should not.
