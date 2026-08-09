---
description: "How NAIS handles pod shutdown (preStop, SIGTERM, load balancing) and which shutdown patterns are correct in a Ktor backend. Read for questions about graceful shutdown, terminationGracePeriodSeconds, aborted requests or readiness during shutdown."
---

# Pod lifecycle and graceful shutdown in NAIS

- NAIS injects a `preStop` hook with `sleep 5` before `SIGTERM` is sent to the application.
- During this period the load balancer stops routing new traffic to the pod.
- Readiness probes are **not** involved in the shutdown flow in NAIS.
- Setting readiness=false manually in application code therefore has no effect and is an anti-pattern.
- The application only needs to: (a) drain in-flight requests and (b) shut down cleanly.
- In this repository: `EngineMain` already installs a JVM shutdown hook, and resources close through the Ktor DI `.cleanup { }` blocks — `ConsumerRunner.close()` and `BackgroundLoop.close()` join their coroutines with a 5-second timeout, and the Hikari pool, the shared `HttpClient` and the Kafka producer close the same way (see `/kotlin-ktor` and `src/main/kotlin/no/nav/budstikka/bootstrap/WorkerModule.kt`). Do not build your own readiness toggles.
- Do not lower `terminationGracePeriodSeconds` below the default of `30` seconds.
- A lower grace period reduces the time the app has for draining and controlled shutdown.
- A short grace period increases the risk of aborted calls and unfinished cleanup.
