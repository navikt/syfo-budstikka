---
description: "Look this up when introducing alerts (PrometheusRule) for this service: consumer lag, inbox/delivery failures, worker failures, heartbeat staleness and Slack routing."
---

# Alerting for syfo-budstikka

No alert configuration exists in this repository today — no `PrometheusRule`, no legacy
`nais.io/v1` `Alert`. This reference says what to alert on for this app when alerts are
introduced, derived from the metrics the app exports and the checked-in dashboard already
queries (`grafana/dashboards/syfo-budstikka.json`).

## What to alert on for this app

The app has no public API — its only HTTP traffic is the internal probes
(`src/main/kotlin/no/nav/budstikka/api/InternalApi.kt`) — so HTTP error-rate and latency alerts
would only measure the probes and are meaningless here. The symptoms that matter are pipeline
symptoms:

- **Consumer lag** — `kafka_consumer_fetch_manager_records_lag_max{topic="team-esyfo.budstikka.v1"}`,
  the metric the app exports and the dashboard queries. Sustained growth means messages arrive
  faster than they are processed.
- **Inbox and delivery failures** — `inbox_message_failed_total` climbing, and
  `delivery_total{result="failed"}` per `channel`.
- **Worker failures** — `worker_failures_total{worker=...}`; the dashboard already tracks
  `worker="inbox-message"` iteration failures.
- **Absence of heartbeat** — a stale consumer heartbeat fails `is_alive` and the platform
  restarts the pod (`docs/helsesjekk.md`), so it surfaces as restarts:
  `kube_pod_container_status_restarts_total` increasing, or `up{app="syfo-budstikka"}` targets
  disappearing.
- **Dead-letter growth** — poison messages land in the dead-letter table and log
  "Poison inbox message dead-lettered" (`infrastructure/kafka/consumer/InboxMessageHandler.kt`).
  No Prometheus counter exists for this yet, so either add one first or alert on the Loki log
  line.

## Mechanism

NAIS alerting today uses the Prometheus Operator's `PrometheusRule` resource
(`apiVersion: monitoring.coreos.com/v1`), not the legacy `nais.io/v1` `Alert` (Alerterator).
Routing is label-driven: the `namespace` label (here `team-esyfo`) routes the notification to
the team's Slack channel as configured in Nais Console, and `severity`
(`critical`/`warning`/`info`) sets the level. Check the NAIS docs (doc.nais.io, observability →
alerting) for the current reference before writing rules.

The team channel is [#esyfo](https://nav-it.slack.com/archives/C012X796B4L) (see `README.md`).

## Rules of thumb

- Short, stable alert names; always add `summary` and `description` annotations
- `warning` for things that should be looked into, `critical` for an active incident
- Keep thresholds cautious until traffic patterns are known — test in `dev-gcp` before
  tightening them in prod
- Avoid many near-identical alerts with small variations in threshold
- Grill production thresholds and alerting channels with `/grilling`. When the choice passes
  the ADR gate, recommend the documented route and wait for the user's choice before
  `/domain-modeling` records it.
