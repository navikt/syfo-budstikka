---
description: "Explains the existing Micrometer setup in syfo-budstikka: shared registry, application port, worker metrics, labels, and health/scrape routes. Read when extending metrics."
---

# Micrometer in syfo-budstikka

## Sources in the repository

- `bootstrap/DependencyInjection.kt` creates one
  `PrometheusMeterRegistry(PrometheusConfig.DEFAULT)`.
- `infrastructure/metrics/MetricsPlugin.kt` installs `MicrometerMetrics` and
  JVM/process binders on the same registry.
- `api/InternalApi.kt` scrapes the registry at `/internal/metrics`.
- `application/port/DispatchMetrics.kt` keeps the application layer free of
  Micrometer.
- `infrastructure/metrics/MicrometerDispatchMetrics.kt` maps the port to
  counters with bounded labels.
- `infrastructure/worker/BackgroundLoop.kt` demonstrates counters/timers for
  technical worker signals.

## Extension pattern

When domain code needs a new signal:

1. Put the operation on a small port in the application layer.
2. Implement the port with `MeterRegistry` in infrastructure.
3. Register the adapter in Ktor's `DependencyRegistry`.
4. Inject the port into the application service, not the registry.
5. Test the adapter's metric names and labels with a test registry.

For technical infrastructure signals, the class can receive `MeterRegistry`
directly, as `BackgroundLoop` does.

```kotlin
private val resultCounter =
    Counter.builder("dispatch.result")
        .tag("channel", channel.name)
        .tag("result", result)
        .register(registry)
```

The Micrometer lookup name is dot-separated; the Prometheus scrape normalizes it
to snake_case and adds `_total` to counters. Test both through the appropriate
interface: `MeterRegistry.get(...)` in an adapter test and scrape text in a
Ktor/integration test.

## Labels

Good labels have a small, known value space: `channel`, `operation`, `result`,
`worker`, `handler`. Do not use `eventId`, `reference`, national identity
numbers, raw exception messages, URLs, or trace IDs.

## Health and scrape

Do not create alternative routes. Extend the existing
`LivenessCheck`/`HealthCheck` if the semantics actually change, and keep the
manifests synchronized with:

```text
/internal/health/is_alive
/internal/health/is_ready
/internal/metrics
```
