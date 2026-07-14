package no.nav.budstikka.infrastructure.metrics

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.di.dependencies
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Installerer [MicrometerMetrics]-pluginen på det delte [PrometheusMeterRegistry] (issue #28). Gir
 * automatisk `ktor_http_server_requests_seconds_*` (med route/method/status som labels) og binder
 * JVM-/prosess-metrikker (`jvm_*`, `process_*`) på samme register som `/internal/metrics` scraper.
 *
 * Kalles etter DI-registreringen slik at registeret finnes; domenemetrikkene (via
 * [no.nav.budstikka.application.port.DispatchMetrics]) og Kafka-klientmetrikkene teller på det
 * samme registeret.
 */
fun Application.installMetrics() {
    val registry: PrometheusMeterRegistry by dependencies
    install(MicrometerMetrics) {
        this.registry = registry
        meterBinders =
            listOf(
                JvmMemoryMetrics(),
                JvmGcMetrics(),
                JvmThreadMetrics(),
                ClassLoaderMetrics(),
                ProcessorMetrics(),
            )
    }
}
