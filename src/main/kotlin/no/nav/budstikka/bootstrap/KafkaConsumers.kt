package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.application.logging.ApplicationMdc
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.infrastructure.kafka.consumer.ConsumerRunner

internal fun Application.startKafkaConsumers() {
    val logger = applicationLogger()
    val runners: List<ConsumerRunner<*, *>> by dependencies
    runners.forEach { runner ->
        ApplicationMdc.putCloseable(MdcKeys.CONSUMER, runner.coroutineName).use {
            logger.info("Starting Kafka consumer")
            runner.start { error ->
                // Unrecoverable errors (bad credentials/config) stop the consumer loop. Once it stops
                // updating its heartbeat, is_alive reports stale and the platform restarts the pod;
                // restarting in-process would just keep hitting the same fault. See docs/helsesjekk.md.
                logger.event(BootstrapLogEvents.kafkaConsumerFatal, error)
            }
        }
    }
}
