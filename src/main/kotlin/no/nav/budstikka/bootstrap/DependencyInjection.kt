package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.infrastructure.database.config.databaseModule
import no.nav.budstikka.infrastructure.database.config.toDatabaseConfig
import no.nav.budstikka.infrastructure.kafka.config.kafkaModule
import no.nav.budstikka.infrastructure.kafka.config.toKafkaConfig
import no.nav.budstikka.infrastructure.task.config.toTaskConfig

internal fun Application.installDependencyInjection() {
    val config = environment.config
    dependencies {
        provide { config.toDatabaseConfig() }
        provide { config.toKafkaConfig() }
        provide { config.toTaskConfig() }
        provide { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
        databaseModule()
        kafkaModule()
        taskModule()
        livenessModule()
    }
}
