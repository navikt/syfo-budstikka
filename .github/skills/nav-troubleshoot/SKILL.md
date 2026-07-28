---
name: nav-troubleshoot
description: "Diagnose NAIS runtime failures in the Ktor backend. Use when pod, authentication, Kafka, database, or observability behavior fails in a production-like environment."
---

# Nav production troubleshooting — platform diagnosis

Structured diagnosis trees for NAIS runtime symptoms in this repository. Use this
skill when something **fails in operation** (pod crash, 401/403, consumer lag,
database timeout), not when designing or changing schema, manifests, or auth.

This skill routes symptom → diagnosis tree. The fix discipline (feedback loop,
reproduction, hypotheses, regression test) belongs to `/diagnosing-bugs`: start
here to locate the cause, then go there to close the defect. Generic
Kubernetes/Kafka/SQL knowledge is not repeated here.

## Workflow

1. **Identify the symptom** before running commands: what fails, in which
   `cluster`/`namespace`, and since when?
2. **Detect the actual stack in the path**: plain Apache Kafka versus Rapids &
   Rivers; Azure AD versus TokenX versus ID-porten/Maskinporten. Diagnosis must
   match what the application runs, not what the manifest could contain.
3. **Follow the diagnosis tree** in the appropriate `references/*.md`, step by step.
4. **Recommend the least invasive fix first**; escalate only when necessary.
5. **Close the defect through `/diagnosing-bugs`** with a regression test where
   there is a correct seam, then return fresh green evidence in KOKK_RESULT/PR.

## Symptom map

| Symptom | Start here |
|---------|-----------|
| Pod does not start / CrashLoopBackOff / OOMKilled / ImagePullBackOff / Pending | [references/pod-diagnose.md](./references/pod-diagnose.md) |
| 401 Unauthorized / 403 Forbidden (TokenX / Azure AD / Texas) | [references/auth-diagnose.md](./references/auth-diagnose.md) |
| Kafka consumer lag / messages not processed | [references/kafka-diagnose.md](./references/kafka-diagnose.md) |
| Database connection failure / HikariCP pool exhaustion / Flyway failure | [references/database-diagnose.md](./references/database-diagnose.md) |
| Error rate, latency, or restarts with conflicting metric, log, and trace signals | [references/observability-diagnose.md](./references/observability-diagnose.md) |
| Slow response time | See the short tree below |
| Deployment failure | See the short tree below |

## Performance problems (short)

```
Slow response time
├── Where is the bottleneck?
│   └── Mimir (http_server_requests_seconds), Tempo (trace), Loki (log): same time window
├── Database queries? → EXPLAIN ANALYZE, N+1, pagination (see /postgresql-review)
├── Downstream service slow? → Ktor-client timeout/retry, circuit breaker, caching
└── Resource constraint?
    ├── CPU throttling → NEVER set NAIS CPU limits (use requests only)
    └── Memory pressure → raise `resources.limits.memory` (see /nais-manifest)
```

See [references/observability-diagnose.md](./references/observability-diagnose.md)
for Nav-specific Mimir/Loki/Tempo diagnosis. Measure before fixing: establish a
baseline with a Micrometer timer, `measureTimedValue {}`, or `EXPLAIN ANALYZE`, then bisect.

## Deployment problems (short)

```
Deployment fails
├── GitHub Actions failure? → Build/Docker/Push: check action log and GAR access
├── NAIS deployment failure?
│   ├── "invalid manifest" → validate YAML (see /nais-manifest)
│   ├── "unauthorized" → check deploy key / workload identity
│   └── "resource quota exceeded" → team quota
└── Deployment succeeds but app fails? → use references/pod-diagnose.md
```

## Related skills

- `/nais-manifest` — manifest structure, resources, probes, `accessPolicy`,
  GCP Postgres, and Kafka pool
- `/auth-overview` — Azure AD, TokenX, ID-porten, Maskinporten, and the Texas
  sidecar behind authentication diagnosis
- `/kafka-topic` — consumer/producer patterns, SSL environment, idempotency,
  and Rapids & Rivers
- `/flyway-migration` and `/postgresql-review` — schema, migration, query, and
  index review at design time
- `/observability-setup` — establishes Micrometer/Prometheus and
  Mimir/Loki/Tempo signals that this skill diagnoses
- `/diagnosing-bugs` — feedback loop, reproduction, hypotheses, and regression
  test; tracks work in a task-scoped brief aligned with Grillmester's loop

## Boundaries

### Always

- Start with the symptom; do not speculate before checking logs and events.
- Follow the tree step by step; verify `cluster`, `namespace`, and app name before concluding.
- Check `kubectl logs --previous` for CrashLoopBackOff.
- Respect the actual stack; do not suggest a Rapids & Rivers fix for a plain Kafka consumer or vice versa.

### Ask first

- Change production configuration (resources, replicas, secrets, accessPolicy).
- Restart pods in production.
- Change database configuration or `maximumPoolSize`.

### Never

- Change secrets directly in the cluster; use source control and NAIS.
- Run `kubectl delete pod` in production without understanding the cause.
- Ignore `OOMKilled`: it will return.
- Set CPU limits on NAIS: they cause throttling.
- Log `fnr`, tokens, names, or special-category data while troubleshooting; log
  `Nav-Call-Id`/`callId`, not personal data.
