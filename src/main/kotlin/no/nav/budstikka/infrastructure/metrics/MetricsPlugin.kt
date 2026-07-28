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
 * Installs [MicrometerMetrics] on the shared [PrometheusMeterRegistry] (issue #28). Automatically
 * provides `ktor_http_server_requests_seconds_*` (route/method/status labels) and binds JVM/process
 * metrics (`jvm_*`, `process_*`) to the same registry scraped at `/internal/metrics`.
 *
 * Call after DI registration so the registry exists; domain metrics through
 * [no.nav.budstikka.application.port.DispatchMetrics] and Kafka client metrics count in the same registry.
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
