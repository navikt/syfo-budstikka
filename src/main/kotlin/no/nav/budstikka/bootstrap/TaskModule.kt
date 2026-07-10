package no.nav.budstikka.bootstrap

import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import no.nav.budstikka.application.InboxMessageTask
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import no.nav.budstikka.infrastructure.task.BaseTask
import no.nav.budstikka.infrastructure.task.config.TaskConfig

fun DependencyRegistry.taskModule() {
    provide<List<BaseTask>> {
        val taskConfig = resolve<TaskConfig>()
        listOf(
            InboxMessageTask(
                repository = resolve<InboxMessageRepository>(),
                config = taskConfig.inboxMessage,
            ),
        )
    }.cleanup { tasks ->
        tasks.forEach(AutoCloseable::close)
    }
}
