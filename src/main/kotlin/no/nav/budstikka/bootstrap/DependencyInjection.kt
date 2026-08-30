package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.delivery.DeliveryMetrics
import no.nav.budstikka.application.inbox.InboxMetrics
import no.nav.budstikka.application.observability.OperationalQueueMetrics
import no.nav.budstikka.application.retention.RetentionMetrics
import no.nav.budstikka.infrastructure.auth.config.authModule
import no.nav.budstikka.infrastructure.auth.config.toTexasConfig
import no.nav.budstikka.infrastructure.client.clientModule
import no.nav.budstikka.infrastructure.client.config.toArbeidsgiverNotifikasjonConfig
import no.nav.budstikka.infrastructure.client.config.toDocumentDistributorConfig
import no.nav.budstikka.infrastructure.client.config.toKrrConfig
import no.nav.budstikka.infrastructure.client.config.toNarmesteLederConfig
import no.nav.budstikka.infrastructure.client.config.toPdlConfig
import no.nav.budstikka.infrastructure.config.toPlatformConfig
import no.nav.budstikka.infrastructure.database.config.databaseModule
import no.nav.budstikka.infrastructure.database.config.toDatabaseConfig
import no.nav.budstikka.infrastructure.kafka.config.kafkaModule
import no.nav.budstikka.infrastructure.kafka.config.toKafkaConfig
import no.nav.budstikka.infrastructure.metrics.MicrometerDeliveryMetrics
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics
import no.nav.budstikka.infrastructure.metrics.MicrometerOperationalQueueMetrics
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionMetrics
import no.nav.budstikka.infrastructure.replay.DeadLetterReplayer
import no.nav.budstikka.infrastructure.worker.config.toWorkerConfig

/**
 * [overrides] runs LAST so a test or local run can replace ports with fakes. With
 * `ktor.di.conflictPolicy = "OverridePrevious"` (only in test/local configuration), the last
 * registration wins; production uses the default policy, where a duplicate registration throws and
 * catches mistakes.
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
        provide { config.toArbeidsgiverNotifikasjonConfig() }
        provide { config.toKrrConfig() }
        provide { config.toNarmesteLederConfig() }
        provide { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
        provide<InboxMetrics> { MicrometerInboxMetrics(resolve<PrometheusMeterRegistry>()) }
        provide<DeliveryMetrics> { MicrometerDeliveryMetrics(resolve<PrometheusMeterRegistry>()) }
        provide<OperationalQueueMetrics> { MicrometerOperationalQueueMetrics(resolve<PrometheusMeterRegistry>()) }
        provide<RetentionMetrics> { MicrometerRetentionMetrics(resolve<PrometheusMeterRegistry>()) }
        databaseModule()
        provide { DeadLetterReplayer(resolve(), resolve()) }
        kafkaModule()
        authModule()
        clientModule()
        gateModule()
        workerModule()
        livenessModule()
        overrides()
    }
}
