package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.infrastructure.auth.config.authModule
import no.nav.budstikka.infrastructure.auth.config.toTexasConfig
import no.nav.budstikka.infrastructure.client.clientModule
import no.nav.budstikka.infrastructure.client.config.toDocumentDistributorConfig
import no.nav.budstikka.infrastructure.client.config.toPdlConfig
import no.nav.budstikka.infrastructure.config.toPlatformConfig
import no.nav.budstikka.infrastructure.database.config.databaseModule
import no.nav.budstikka.infrastructure.database.config.toDatabaseConfig
import no.nav.budstikka.infrastructure.kafka.config.kafkaModule
import no.nav.budstikka.infrastructure.kafka.config.toKafkaConfig
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics
import no.nav.budstikka.infrastructure.worker.config.toWorkerConfig

/**
 * [overrides] kjøres SIST slik at et test-/lokalt løp kan bytte porter mot fakes. Med
 * `ktor.di.conflictPolicy = "OverridePrevious"` (kun i test-/lokal-konfig) vinner den siste
 * registreringen; i prod er policyen default og en duplikat-registrering kaster (fanger uhell).
 */
internal fun Application.installDependencyInjection(overrides: DependencyRegistry.() -> Unit = {}) {
    val config = environment.config
    dependencies {
        provide { config.toDatabaseConfig() }
        provide { config.toPlatformConfig() }
        provide { config.toKafkaConfig() }
        provide { config.toWorkerConfig() }
        provide { config.toTexasConfig() }
        provide { config.toPdlConfig() }
        provide { config.toDocumentDistributorConfig() }
        provide { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
        provide<DispatchMetrics> { MicrometerDispatchMetrics(resolve<PrometheusMeterRegistry>()) }
        databaseModule()
        kafkaModule()
        authModule()
        clientModule()
        workerModule()
        livenessModule()
        overrides()
    }
}
