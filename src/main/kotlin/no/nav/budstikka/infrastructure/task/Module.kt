package no.nav.budstikka.infrastructure.task

import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import no.nav.budstikka.application.InboxMessageTask
import java.time.Duration

fun DependencyRegistry.taskModule() {
    provide<List<BaseTask>> {
        listOf(
            InboxMessageTask(
                repository = resolve<InboxMessageRepository>(),
                interval = Duration.ofSeconds(INBOX_MESSAGE_TASK_INTERVAL_SECONDS),
            ),
        )
    }.cleanup { tasks ->
        tasks.forEach(AutoCloseable::close)
    }
}

private const val INBOX_MESSAGE_TASK_INTERVAL_SECONDS = 5L
