---
description: "Slå opp ved skriving av Micrometer-metrikker i Ktor: MeterRegistry-oppsett, Counter/Timer/Gauge/DistributionSummary, domene- og Kafka-metrikker, og health-routes i syfo-budstikka."
---

# Micrometer og health i Ktor

Backend-mønstre for Kotlin/Ktor i syfo-budstikka. Ktor har ingen Actuator — du eier registry-oppsettet selv.

## MeterRegistry-oppsett

Opprett ett `PrometheusMeterRegistry`, installer `MicrometerMetrics`-pluginen, og del samme instans via Koin slik at domenekode måler mot samme registry som HTTP-metrikkene.

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

Registrer registryet som singleton i Koin og injiser det der du måler:

```kotlin
val metricsModule = module {
    single { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
}
```

`MicrometerMetrics` gir automatisk `ktor_http_server_requests_seconds` med tags for route, method og status. Trenger du percentiler fra Prometheus, slå på histogram:

```kotlin
install(MicrometerMetrics) {
    this.registry = registry
    distributionStatisticConfig = DistributionStatisticConfig.Builder()
        .percentilesHistogram(true)
        .build()
}
```

## Counter

For ting som bare kan øke.

```kotlin
class OppgaveService(registry: MeterRegistry) {
    private val opprettet = Counter.builder("oppgaver_opprettet_total")
        .description("Antall opprettede oppgaver")
        .tag("kilde", "api")
        .register(registry)

    fun opprett() {
        opprettet.increment()
    }
}
```

Bruk `rate()` / `increase()` i Prometheus for hastighet over tid.

## Timer

For varighet. For percentiler må timeren publisere histogram.

```kotlin
class BehandlingService(registry: MeterRegistry) {
    private val behandlingstid = Timer.builder("oppgave_behandlingstid_seconds")
        .description("Behandlingstid for oppgave")
        .publishPercentileHistogram()
        .tag("type", "manuell")
        .register(registry)

    fun behandle(): String =
        requireNotNull(behandlingstid.recordCallable { "ferdig" }) {
            "Timet blokk returnerte null"
        }
}
```

Bruk timer for respons- eller behandlingstid, særlig når du trenger p50/p95/p99.

## Gauge

For en nåverdi, f.eks. køstørrelse eller aktive forbindelser.

```kotlin
class KoMetrics(registry: MeterRegistry) {
    private val koStorrelse = AtomicInteger(0)

    init {
        Gauge.builder("oppgave_ko_storrelse", koStorrelse) { it.get().toDouble() }
            .description("Antall ventende oppgaver")
            .register(registry)
    }

    fun oppdater(antall: Int) = koStorrelse.set(antall)
}
```

## DistributionSummary

For fordelinger som ikke er tid.

```kotlin
class PayloadMetrics(registry: MeterRegistry) {
    private val storrelse = DistributionSummary.builder("melding_payload_size_bytes")
        .description("Størrelse på innkommende melding")
        .baseUnit("bytes")
        .publishPercentileHistogram()
        .register(registry)

    fun record(bytes: Int) = storrelse.record(bytes.toDouble())
}
```

## Domenemetrikker

Velg målinger som viser om løsningen fungerer, ikke bare om JVM-en lever.

**Gode kandidater**
- antall behandlede domenehendelser per type
- andel feil/suksess for viktige flyter
- ventende oppgaver i kø
- behandlingstid per steg eller event-type

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

Mål mottatte events, vellykket behandling, feil, behandlingstid og consumer lag/køstørrelse. Bruk labels som `event_type`, `result`, `topic` eller `consumer_group` — aldri message key, payload-id, fnr eller aktør-id.

## Health-routes

NAV-konvensjon er enkle interne routes, ikke Actuator. Tilpass alltid stiene til NAIS-manifestet.

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

**Tommelfingerregler**
- `isalive` (liveness) svarer på om prosessen bør restartes — hold den triviell
- `isready` (readiness) svarer på om instansen kan ta trafikk nå — la den avhenge av faktiske avhengigheter (Postgres-pool, Kafka)
- Ikke legg tung logikk i health checks
- Hold detaljer fri for sensitiv informasjon
- Netty/`EngineMain` håndterer `SIGTERM` og graceful shutdown — du trenger ikke manuell readiness-vipping ved nedstenging
