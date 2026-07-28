# Observability diagnosis — when metrics, logs, and traces disagree

Use this reference when the Ktor backend (`no.nav.budstikka`) runs but symptoms
are unclear: high error rate without a clear stack trace, latency without a
bottleneck, restarts without a root cause, or conflicting Mimir, Loki, and Tempo signals.

This is a NAV-specific diagnosis guide for **Mimir + Loki + Tempo on Nais**. It does not teach Prometheus or Grafana from scratch. It helps you choose the right starting point and avoid common misinterpretations.

## Initial checks

- Establish the **symptom**: error rate, latency, restart, missing traces, or unclear logs
- Establish the **scope**: app, namespace, cluster (`dev-gcp` vs `prod-gcp`), and time window
- Establish the **stack**: HTTP-only or Kafka as well, and whether Micrometer/auto-instrumentation is active
- Check whether symptoms started with the latest deploy, a configuration change, or increased traffic

## Diagnostic tree

```text
Observability signals disagree
├── High error rate?
│   ├── Start in Mimir: rate of 4xx/5xx per route
│   ├── Find the same time window in Loki
│   └── If logs contain trace_id → look it up in Tempo
├── High latency?
│   ├── Start in Mimir: p95/p99 per route or operation
│   ├── Tempo: find slow spans
│   └── Verify whether the slowness is in the app, DB, or downstream call
├── Restarts / OOM / throttling?
│   ├── Start in pod-diagnose.md + Mimir for memory/restarts
│   ├── Loki: inspect the last logs before the restart
│   └── Do not conclude "app bug" before limits/requests are checked
└── Missing traces or correlation?
    ├── Check that the app actually emits spans in this runtime
    ├── Check that logs contain trace_id/callId
    └── Check that you are viewing the correct service and cluster
```

## Starting point by symptom

### 1. Error rate without a clear cause

Start in **Mimir** to identify the failing route or operation. Then go to **Loki** for the same time window. Do not start with free-text searches across all logs.

- Filter first on stable labels: `app`, `namespace`, `cluster`, and optionally route/status
- Look for a 5xx spike before interpreting 4xx as a server error (4xx is often client/auth-side)
- Once you find a concrete log example, follow its `trace_id` or `callId`

### 2. Latency without errors

Start in **Mimir** for p95/p99 (`http_server_requests_seconds`) and then go to **Tempo**.

- Determine whether the slowness is consistent or limited to individual traces
- Compare route/operation before and after the latest deploy
- If one span dominates, check DB calls, downstream HTTP (Ktor client), or Kafka waiting before proposing a code change

### 3. Restarts, memory pressure, or CPU problems

Combine **pod-diagnose.md** and observability:

- Mimir confirms the trend: memory approaching its limit, restart count, and possible throttling
- Loki shows what the app did immediately before the restart
- `OOMKilled` is generally not solved until the memory profile or limits have been assessed
- CPU throttling on Nais is often self-inflicted when CPU limits are set — remove them

### 4. "Tempo shows strange traces"

Common NAV gotcha: Tempo searches can return matches that do not actually belong to the service you are troubleshooting.

- Verify `rootServiceName` or the equivalent service name before interpreting the trace result
- Check the correct cluster (`dev-gcp` vs `prod-gcp`)
- If the app does not emit spans yet, return to logs and metrics instead of guessing

### 5. "Loki finds too much or too little"

- Start with labels first, then JSON parsing
- Do not search broadly for terms such as `error` without `app` and a time window
- If logs lack `trace_id` or `callId`, record it as an observability gap, not proof that the fault lies elsewhere

## NAV gotchas

- **Use the same time window first**: do not compare a trace from now with logs from the previous deploy
- **Use the same identifier**: use `trace_id` or `Nav-Call-Id` through the entire chain when available
- **Use the correct environment**: verify `cluster`, `namespace`, and app name before drawing conclusions
- **Missing signals are also findings**: no traces can mean missing instrumentation, an incorrect service name, or the wrong environment selected
- **Do not jump straight to dashboard fixes**: diagnose the runtime fault first; improve dashboards afterwards
- **PII**: log `Nav-Call-Id`/`callId`, never national identity numbers/tokens/names — this also applies to temporary debug logs

## When to escalate

- Escalate to [pod-diagnose.md](./pod-diagnose.md) when the issue is effectively restart, readiness, or resource exhaustion
- Escalate to [database-diagnose.md](./database-diagnose.md) when slow spans or logs point to a pool/query problem
- Escalate to [auth-diagnose.md](./auth-diagnose.md) when the error rate is primarily 401/403
- Escalate to `/diagnosing-bugs` (the perf branch) when the root cause requires a baseline measurement and bisect, or when the finding is missing instrumentation / incorrect labels / missing `trace_id` in logs — in that case, elevate it to an ADR in `docs/adr/`
