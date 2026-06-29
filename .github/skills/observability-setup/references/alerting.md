---
description: "Slå opp ved skriving av Prometheus-alerts og NAIS Alert-ressurser for syfo-budstikka: feilrate, latency, pod restarts, Kafka lag og Slack-ruting."
---

# Alerting og varsling i NAIS

Praktiske mønstre for Prometheus-regler og varsling til Slack via NAIS. Prioriter varsler som peker på reelle bruker- eller driftsproblemer teamet faktisk må reagere på.

## Vanlige varslingsmønstre

### Høy feilrate

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

### Latency-spike

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

### Pod restart / utilgjengelighet

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

### Kafka / køproblemer

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

## NAIS-mønstre for varslingsregler

- Bruk korte, stabile alert-navn
- Legg alltid til `summary`, `description` og helst runbook-lenke
- `warning` for ting som bør undersøkes, `critical` for aktiv hendelse
- Alert på symptomer før interne indikatorer
- Test terskler i `dev-gcp` før du strammer dem i prod
- Unngå mange nesten-like varsler med små variasjoner i terskel
- Endring av produksjonsterskler er en beslutning som bør grilles og dokumenteres (`.grill/adr/`)

## Slack-ruting via NAIS Alert

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

Velg kanal og `prependText` med omtanke. Kritiske varsler kan bruke `@here`; støyende varsler bør normalt ikke gjøre det.
