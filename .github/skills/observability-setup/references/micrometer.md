---
description: "Look this up when writing Micrometer metrics in Ktor: MeterRegistry setup, Counter/Timer/Gauge/DistributionSummary, domain and Kafka metrics, and health routes in this repository."
---

# Micrometer and health in Ktor

Backend patterns for Kotlin/Ktor in this repository. Ktor has no Actuator — you own the registry setup yourself.

## MeterRegistry setup

Create a single `PrometheusMeterRegistry`, install the `MicrometerMetrics` plugin, and share the same instance via Koin so that domain code measures against the same registry as the HTTP metrics.

```kotlin
// build.gradle.kts
//   implementation(ktorLibs.server.metrics.micrometer)
//   implementation(libs.micrometer.registry.prometheus)
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

fun Application.installMetrics(registry: PrometheusMeterRegistry) {
    install(MicrometerMetrics) {
        this.registry = registry
        meterBinders = listOf(JvmMemoryMetrics(), JvmGcMetrics(), ProcessorMetrics())
    }
    routing {
        get("/internal/prometheus") { call.respond(registry.scrape()) }
    }
}
```

Register the registry as a singleton in Koin and inject it wherever you measure:

```kotlin
val metricsModule = module {
    single { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
}
```

`MicrometerMetrics` automatically provides `ktor_http_server_requests_seconds` with tags for route, method and status. If you need percentiles from Prometheus, turn on the histogram:

```kotlin
install(MicrometerMetrics) {
    this.registry = registry
    distributionStatisticConfig = DistributionStatisticConfig.Builder()
        .percentilesHistogram(true)
        .build()
}
```

## Counter

For things that can only increase.

```kotlin
class OppgaveService(registry: MeterRegistry) {
    private val opprettet = Counter.builder("oppgaver_opprettet_total")
        .description("Number of oppgaver created")
        .tag("kilde", "api")
        .register(registry)

    fun opprett() {
        opprettet.increment()
    }
}
```

Use `rate()` / `increase()` in Prometheus for the rate over time.

## Timer

For duration. For percentiles the timer must publish a histogram.

```kotlin
class BehandlingService(registry: MeterRegistry) {
    private val behandlingstid = Timer.builder("oppgave_behandlingstid_seconds")
        .description("Processing time for an oppgave")
        .publishPercentileHistogram()
        .tag("type", "manuell")
        .register(registry)

    fun behandle(): String =
        requireNotNull(behandlingstid.recordCallable { "ferdig" }) {
            "Timed block returned null"
        }
}
```

Use a timer for response or processing time, especially when you need p50/p95/p99.

## Gauge

For a current value, e.g. queue size or active connections.

```kotlin
class KoMetrics(registry: MeterRegistry) {
    private val koStorrelse = AtomicInteger(0)

    init {
        Gauge.builder("oppgave_ko_storrelse", koStorrelse) { it.get().toDouble() }
            .description("Number of pending oppgaver")
            .register(registry)
    }

    fun oppdater(antall: Int) = koStorrelse.set(antall)
}
```

## DistributionSummary

For distributions that are not time.

```kotlin
class PayloadMetrics(registry: MeterRegistry) {
    private val storrelse = DistributionSummary.builder("melding_payload_size_bytes")
        .description("Size of an incoming message")
        .baseUnit("bytes")
        .publishPercentileHistogram()
        .register(registry)

    fun record(bytes: Int) = storrelse.record(bytes.toDouble())
}
```

## Domain metrics

Choose measurements that show whether the solution works, not just whether the JVM is alive.

**Good candidates**
- number of processed domain events per type
- error/success ratio for important flows
- pending tasks in a queue
- processing time per step or event type

```kotlin
class BehandlingMetrics(registry: MeterRegistry) {
    private val resultat = registry // tag-basert: én metrikk, flere resultater

    fun tellResultat(result: String) =
        Counter.builder("oppgaver_behandlet_total")
            .tag("result", result) // "success" | "failure"
            .register(resultat)
            .increment()
}
```

### Kafka

Measure received events, successful processing, errors, processing time and consumer lag/queue size. Use labels such as `event_type`, `result`, `topic` or `consumer_group` — never message key, payload id, fnr or aktør-id.

## Health routes

The NAV convention is simple internal routes, not Actuator. Always align the paths with the NAIS manifest.

```kotlin
routing {
    get("/internal/isalive") { call.respondText("OK") }
    get("/internal/isready") {
        if (kafkaConsumer.isReady() && dataSource.isHealthy()) {
            call.respondText("OK")
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, "NOT READY")
        }
    }
}
```

**Rules of thumb**
- `isalive` (liveness) answers whether the process ought to be restarted — keep it trivial
- `isready` (readiness) answers whether the instance can take traffic right now — let it depend on actual dependencies (Postgres pool, Kafka)
- Do not put heavy logic in health checks
- Keep the details free of sensitive information
- Netty/`EngineMain` handles `SIGTERM` and graceful shutdown — you do not need to flip readiness manually at shutdown
