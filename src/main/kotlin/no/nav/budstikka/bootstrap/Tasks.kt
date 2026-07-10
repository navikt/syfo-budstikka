package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.infrastructure.task.BaseTask

internal fun Application.startTasks() {
    val tasks: List<BaseTask> by dependencies
    tasks.forEach { task -> task.start() }
}
