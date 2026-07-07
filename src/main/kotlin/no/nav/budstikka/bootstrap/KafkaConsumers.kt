package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.infrastructure.kafka.config.ConsumerRunner

internal fun Application.startKafkaConsumers() {
    val runners: List<ConsumerRunner<*, *>> by dependencies

    runners.forEach { runner ->
        runner.start { error ->
            // Transient failures are retried internally; this only fires on unrecoverable errors
            // (bad credentials/config). Fail the liveness probe so the platform restarts the pod,
            // since restarting the consumer in-process would just keep hitting the same fault.
            log.error("Kafka consumer hit a fatal error, marking application as not alive", error)
        }
    }
}
