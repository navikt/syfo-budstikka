package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.application.logging.ApplicationMdc
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.infrastructure.worker.BackgroundLoop

internal fun Application.startWorkers() {
    val logger = applicationLogger()
    val workers: List<BackgroundLoop> by dependencies
    workers.forEach { worker ->
        ApplicationMdc.putCloseable(MdcKeys.WORKER, worker.name).use {
            logger.info("Starting worker")
            worker.start()
        }
    }
}
