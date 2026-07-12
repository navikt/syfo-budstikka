package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.application.MdcKeys
import no.nav.budstikka.infrastructure.task.BaseTask
import org.slf4j.MDC

internal fun Application.startTasks() {
    val tasks: List<BaseTask> by dependencies
    tasks.forEach { task ->
        MDC.putCloseable(MdcKeys.TASK, task.name).use {
            log.info("Starting task")
            task.start()
        }
    }
}
