---
name: nav-troubleshoot
description: "Use when the Ktor backend fails at RUNTIME on NAIS: pod does not start / CrashLoopBackOff / OOMKilled, 401/403, Kafka consumer lag, DB/HikariCP/Flyway errors at startup, or signals that disagree across Mimir/Loki/Tempo. For designing schema/manifest/auth, see /nais-manifest and /auth-overview."
---

# Nav Troubleshoot — platform diagnostics

Structured diagnostic trees for runtime symptoms on NAIS in this repository. Use this skill when something **fails at runtime** (pod crashes, 401/403, consumer lag, DB timeout) — not when you are designing or changing schema / manifest / auth.

The skill routes symptom → the right diagnostic tree. The fix discipline (feedback loop, repro, hypotheses, regression test) lives in `/diagnosing-bugs` — start here to locate the cause, go there to close the bug. Generic Kubernetes/Kafka/SQL knowledge is not duplicated here; bring that from your own repertoire.

## Workflow

1. **Identify the symptom** before running commands — what exactly is failing, in which `cluster`/`namespace`, since when?
2. **Detect the actual stack in this code path** — plain Apache Kafka clients vs. Rapids & Rivers, Azure AD vs. TokenX vs. ID-porten/Maskinporten. The diagnosis must match what the app actually runs, not what the manifest could have contained.
3. **Follow the diagnostic tree** in the right `references/*.md` — step by step.
4. **Propose the least invasive fix first**; escalate only if necessary.
5. **Close the bug via `/diagnosing-bugs`** — write a regression test (`./gradlew test`, Ktor `testApplication { }`) where a correct seam exists, and return fresh green evidence to the active task.

## Symptom overview

| Symptom | Start here |
|---------|-----------|
| Pod does not start / CrashLoopBackOff / OOMKilled / ImagePullBackOff / Pending | [references/pod-diagnose.md](./references/pod-diagnose.md) |
| 401 Unauthorized / 403 Forbidden (TokenX / Azure AD / Texas) | [references/auth-diagnose.md](./references/auth-diagnose.md) |
| Kafka consumer lag / messages are not processed | [references/kafka-diagnose.md](./references/kafka-diagnose.md) |
| DB connection errors / HikariCP pool exhaustion / Flyway errors | [references/database-diagnose.md](./references/database-diagnose.md) |
| Error rate, latency or restarts where the signals disagree across metrics, logs and traces | [references/observability-diagnose.md](./references/observability-diagnose.md) |
| Slow response time | See the short tree below |
| Deploy fails | See the short tree below |

## Performance problems (short)

```
Slow response time
├── Where is the bottleneck?
│   └── Mimir (http_server_requests_seconds), Tempo (trace), Loki (log) — same time window
├── Database queries? → EXPLAIN ANALYZE, N+1, pagination (see /postgresql-review)
├── Slow external service? → timeout/retry in the Ktor client, circuit breaker, caching
└── Resource constraint?
    ├── CPU throttling → NEVER set CPU limits on NAIS (use requests only)
    └── Memory pressure → increase `resources.limits.memory` (see /nais-manifest)
```

See [references/observability-diagnose.md](./references/observability-diagnose.md) for NAV-specific diagnostics in Mimir/Loki/Tempo. Measure before you fix — establish a baseline (Micrometer timers, `measureTimedValue {}`, `EXPLAIN ANALYZE`), then bisect.

## Deploy problems (short)

```
Deploy fails
├── GitHub Actions failure? → Build/Docker/Push — check the actions log and GAR access
├── Nais deploy failure?
│   ├── "invalid manifest" → validate the YAML (see /nais-manifest)
│   ├── "unauthorized" → check the deploy key / workload identity
│   └── "resource quota exceeded" → team quota
└── Deploy OK but the app fails? → use references/pod-diagnose.md
```

## Related skills

- `/nais-manifest` — manifest structure, resources, probes, accessPolicy, GCP Postgres, Kafka pool
- `/auth-overview` — Azure AD, TokenX, ID-porten, Maskinporten, the Texas sidecar (the mechanisms behind auth-diagnose)
- `/kafka-topic` — consumer/producer patterns, SSL env vars, idempotency, Rapids & Rivers
- `/postgresql-review` — schema, query and index review (design time)
- `/observability-setup` — Micrometer/Prometheus + Mimir/Loki/Tempo setup (design time; nav-troubleshoot reads the signals, observability-setup establishes them)
- `/diagnosing-bugs` — feedback loop, repro, hypotheses and regression test;
  returns findings and verification to the active task

## Limits

### Always

- Start with the symptom; do not speculate about the cause before logs/events have been checked.
- Follow the diagnostic tree step by step; verify `cluster`/`namespace`/app name before you conclude.
- Check `kubectl logs --previous` on CrashLoopBackOff.
- Respect the app's actual stack — do not propose a Rapids & Rivers fix for a plain Kafka consumer or vice versa.

### Ask first

- Changing production configuration (resources, replicas, secrets, accessPolicy).
- Restarting pods in production.
- Changing database configuration or `maximumPoolSize`.

### Never

- Change secrets directly in the cluster (go through source control / NAIS).
- Run `kubectl delete pod` in prod without understanding the cause.
- Ignore `OOMKilled` — it will come back.
- Set CPU limits on NAIS — it causes throttling.
- Log national identity numbers, tokens, names or special categories of data while troubleshooting — log `Nav-Call-Id`/`callId`, not personal data.
