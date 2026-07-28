---
description: "Provides Prometheus alert and NAIS Alert patterns for error rate, latency, pod restarts, Kafka lag, and Slack routing. Read when adding or changing alerts for syfo-budstikka."
---

# Alerting and notification in NAIS

Practical patterns for Prometheus rules and Slack notifications through NAIS. Prioritize alerts that point to genuine user or operational problems the team must actually respond to.

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
          summary: "High error rate for syfo-budstikka"
          description: "More than 5% of requests fail with 5xx over 10 minutes"
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
    summary: "High latency for syfo-budstikka"
    description: "p95 latency is above 1 second"
```

### Pod restart / unavailability

```yaml
- alert: PodRestarts
  expr: sum(increase(kube_pod_container_status_restarts_total{app="syfo-budstikka"}[15m])) > 3
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Pods restart frequently"
    description: "syfo-budstikka has restarted more than 3 times in 15 minutes"

- alert: ApplicationDown
  expr: sum(up{app="syfo-budstikka"}) == 0
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "Application is down"
    description: "No healthy targets are scraped for syfo-budstikka"
```

### Kafka / queue problems

```yaml
- alert: KafkaConsumerLagHigh
  expr: max(kafka_consumer_lag{app="syfo-budstikka"}) > 10000
  for: 15m
  labels:
    severity: warning
  annotations:
    summary: "High Kafka consumer lag"
    description: "Lag has remained above 10000 for 15 minutes"
```

## NAIS patterns for alerting rules

- Use short, stable alert names
- Always add `summary`, `description`, and preferably a runbook link
- Use `warning` for conditions that should be investigated and `critical` for an active incident
- Alert on symptoms before internal indicators
- Test thresholds in `dev-gcp` before tightening them in prod
- Avoid many near-identical alerts with small threshold variations
- Changing production thresholds is a decision that should be grilled and documented (`docs/adr/`)

## Slack routing through NAIS Alert

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

Choose the channel and `prependText` carefully. Critical alerts can use `@here`; noisy alerts normally should not.
