package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.application.MdcKeys
import no.nav.budstikka.infrastructure.worker.BackgroundLoop
import org.slf4j.MDC

internal fun Application.startWorkers() {
    val workers: List<BackgroundLoop> by dependencies
    workers.forEach { worker ->
        MDC.putCloseable(MdcKeys.WORKER, worker.name).use {
            log.info("Starting worker")
            worker.start()
        }
    }
}
