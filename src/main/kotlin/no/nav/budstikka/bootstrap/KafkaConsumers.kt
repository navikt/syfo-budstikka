package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.infrastructure.config.MdcKeys
import no.nav.budstikka.infrastructure.kafka.consumer.ConsumerRunner
import org.slf4j.MDC

internal fun Application.startKafkaConsumers() {
    val runners: List<ConsumerRunner<*, *>> by dependencies
    runners.forEach { runner ->
        MDC.putCloseable(MdcKeys.CONSUMER, runner.coroutineName).use {
            log.info("Starting Kafka consumer")
            runner.start { error ->
                // Unrecoverable errors (bad credentials/config) stop the consumer loop. Once it stops
                // updating its heartbeat, is_alive reports stale and the platform restarts the pod;
                // restarting in-process would just keep hitting the same fault. See docs/HELSESJEKK.md.
                log.error("Kafka consumer hit a fatal error; loop stopped, liveness will report stale", error)
            }
        }
    }
}
