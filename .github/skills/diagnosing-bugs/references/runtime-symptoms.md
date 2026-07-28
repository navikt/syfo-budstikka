# Runtime symptoms

For production/runtime failures, use the corresponding Nav/Ktor tree in
`/nav-troubleshoot` before returning to diagnosis phases 5–6.

| Symptom | `/nav-troubleshoot` tree |
|---|---|
| Pod fails to start, crashes, OOMKilled, ImagePullBackOff | [Pod diagnosis](../../nav-troubleshoot/references/pod-diagnose.md) |
| 401/403 (TokenX, Azure AD, Texas) | [Authentication diagnosis](../../nav-troubleshoot/references/auth-diagnose.md) |
| Kafka consumer lag or unprocessed messages | [Kafka diagnosis](../../nav-troubleshoot/references/kafka-diagnose.md) |
| DB connection/HikariCP/Flyway failure | [Database diagnosis](../../nav-troubleshoot/references/database-diagnose.md) |
| Divergent error, latency, or restart signals | [Observability diagnosis](../../nav-troubleshoot/references/observability-diagnose.md) |

Propose the least invasive fix first. Ask before changing production
configuration, restarting pods, or changing production pool size.
