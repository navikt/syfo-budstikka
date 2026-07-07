package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.infrastructure.kafka.config.ConsumerRunner
import java.time.Duration

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

    // Drain consumers during ApplicationStopping, which fires before ApplicationStopped where the
    // datasource is closed, so in-flight handlers can still finish their database work.
    monitor.subscribe(ApplicationStopping) {
        // Signal every consumer first so they all drain in parallel, then wait for each.
        runners.forEach { runner ->
            runner.stop()
        }
        runners.forEach { runner ->
            runner.join(Duration.ofSeconds(5))
        }
    }
}
