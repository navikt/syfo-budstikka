package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.dependencies
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.infrastructure.database.config.databaseModule
import no.nav.budstikka.infrastructure.database.config.toDatabaseConfig
import no.nav.budstikka.infrastructure.kafka.config.kafkaModule
import no.nav.budstikka.infrastructure.kafka.config.toKafkaConfig
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
        provide { config.toKafkaConfig() }
        provide { config.toWorkerConfig() }
        provide { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
        databaseModule()
        kafkaModule()
        workerModule()
        livenessModule()
        overrides()
    }
}
