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
 * Installs HTTP, JVM, and process metrics in the shared [PrometheusMeterRegistry]. Call after the
 * registry is registered in dependency injection.
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
