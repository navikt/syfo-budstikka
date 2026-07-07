package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.infrastructure.HealthCheck
import no.nav.budstikka.infrastructure.database.config.databaseHealthCheck
import no.nav.budstikka.infrastructure.database.config.databaseModule
import no.nav.budstikka.infrastructure.database.config.toDatabaseConfig

internal fun Application.installDependencyInjection() {
    val config = environment.config
    dependencies {
        provide { config.toDatabaseConfig() }
        provide { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
        databaseModule()
        provide<HealthCheck> {
            databaseHealthCheck(resolve())
        }
    }
}
