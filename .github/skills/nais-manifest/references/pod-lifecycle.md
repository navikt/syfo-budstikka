---
description: "Explains how NAIS handles pod shutdown through preStop, SIGTERM, and load balancing, and the correct Ktor shutdown patterns. Read when handling graceful shutdown, terminationGracePeriodSeconds, interrupted requests, or readiness during shutdown."
---

# Pod lifecycle and graceful shutdown in NAIS

- NAIS injects a `preStop` hook with `sleep 5` before sending `SIGTERM` to the application.
- During that period, the load balancer stops routing new traffic to the pod.
- Readiness probes are **not** part of NAIS shutdown flow.
- Manually setting readiness=false in application code therefore has no effect and is an anti-pattern.
- The application needs only to drain in-flight requests and close cleanly.
- In Ktor, use `ApplicationStopping`/`ApplicationStopped` events, or
  `embeddedServer(...).stop(gracePeriod, timeout)`, to close Hikari, Kafka
  consumers/producers, and other resources in a controlled way. Do not add
  independent readiness toggles.
- Do not lower `terminationGracePeriodSeconds` below the default 30 seconds.
- A lower grace period reduces time for draining and controlled shutdown.
- A short grace period raises the risk of interrupted calls and unfinished cleanup.
