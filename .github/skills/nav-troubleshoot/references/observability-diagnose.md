# Observability diagnosis — when metrics, logs and traces do not point the same way

Use this reference when this repository's Ktor backend (`no.nav.syfo`) is running but the symptoms are unclear: a high error rate with no clear stack trace, latency with no clear bottleneck, restarts with no certain root cause, or no correlation between Mimir, Loki and Tempo.

This is NAV-specific diagnostic help for **Mimir + Loki + Tempo on Nais**. It does not teach Prometheus or Grafana from scratch. It helps you pick the right starting point and avoid common misreadings.

## First checks

- Establish the **symptom**: error rate, latency, restart, missing traces or unclear logs
- Establish the **scope**: app, namespace, cluster (`dev-gcp` vs `prod-gcp`) and time window
- Establish the **stack**: HTTP-only or Kafka as well, Micrometer/auto-instrumentation active or not
- Check whether the symptoms started at the last deploy, a config change or increased traffic

## Diagnostic tree

```text
The observability signals disagree
├── High error rate?
│   ├── Start in Mimir: rate of 4xx/5xx per route
│   ├── Find the same time window in Loki
│   └── If the logs have trace_id → look it up in Tempo
├── High latency?
│   ├── Start in Mimir: p95/p99 per route or operation
│   ├── Tempo: find the slow spans
│   └── Verify whether the slowness is in the app, the DB or downstream calls
├── Restarts / OOM / throttling?
│   ├── Start in pod-diagnose.md + Mimir for memory/restarts
│   ├── Loki: look at the last logs before the restart
│   └── Do not conclude "app bug" before limits/requests have been checked
└── Missing traces or correlation?
    ├── Check that the app actually emits spans in this runtime
    ├── Check that the logs have trace_id/callId
    └── Check that you are looking at the right service and the right cluster
```

## Starting point per symptom

### 1. Error rate with no clear cause

Start in **Mimir** to find which route or operation is failing. Then go to **Loki** for the same time window. Do not start with a free-text search across all logs.

- Filter on stable labels first: `app`, `namespace`, `cluster`, possibly route/status
- Look for a spike in 5xx before interpreting 4xx as server errors (4xx is often on the client/auth side)
- Once you find one concrete log example: follow `trace_id` or `callId` from there

### 2. Latency without errors

Start in **Mimir** for p95/p99 (`http_server_requests_seconds`), then go to **Tempo**.

- Check whether the slowness is consistent or applies only to individual traces
- Compare route/operation before and after the last deploy
- If one span dominates: check DB calls, downstream HTTP (the Ktor client) or Kafka waiting before proposing a code change

### 3. Restarts, memory pressure or CPU problems

Combine **pod-diagnose.md** and observability:

- Mimir confirms the trend: memory approaching the limit, restart count, possibly throttling
- Loki shows what the app was doing right before the restart
- `OOMKilled` is usually not resolved until the memory profile or the limits have been reviewed
- CPU throttling on Nais is often self-inflicted if CPU limits are set — remove them

### 4. "Tempo shows strange traces"

Common NAV gotcha: a Tempo search can return hits that do not actually belong to the service you are troubleshooting.

- Verify `rootServiceName` or the equivalent service name before interpreting the trace result
- Check the right cluster (`dev-gcp` vs `prod-gcp`)
- If the app does not expose spans yet: go back to logs and metrics instead of guessing

### 5. "Loki finds too much or too little"

- Start with labels first, JSON parsing afterwards
- Do not search broadly for words like `error` without `app` and a time window
- If logs are missing `trace_id` or `callId`, note it as an observability gap, not as evidence that the fault lies elsewhere

## NAV gotchas

- **Same time window first**: do not compare a trace from now with logs from the previous deploy
- **Same identifier**: use `trace_id` or `Nav-Call-Id` through the whole chain where they exist
- **Right environment**: verify `cluster`, `namespace` and app name before drawing conclusions
- **A missing signal is a finding too**: no traces can mean missing instrumentation, the wrong service name, or that the wrong environment is selected
- **Do not jump straight to a dashboard fix**: diagnose the runtime failure first; improve the dashboards afterwards
- **PII**: log `Nav-Call-Id`/`callId`, never national identity numbers/tokens/names — this applies to temporary debug logging too

## When to escalate

- To [pod-diagnose.md](./pod-diagnose.md) when the problem is really a restart, readiness or resource shortage
- To [database-diagnose.md](./database-diagnose.md) when slow spans or logs point to a pool/query problem
- To [auth-diagnose.md](./auth-diagnose.md) when the error rate is primarily 401/403
- To `/diagnosing-bugs` (the perf branch) when the root cause requires a baseline measurement
  and a bisect. Missing instrumentation, wrong labels or a missing `trace_id`
  is a maintained observability gap in the relevant topic document. If the fix
  involves a lasting decision that passes the ADR gate, recommend the documented
  route and wait for the user's choice before `/domain-modeling` records it.
