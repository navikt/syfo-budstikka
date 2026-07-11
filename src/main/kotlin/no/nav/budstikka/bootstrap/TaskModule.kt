package no.nav.budstikka.bootstrap

import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import no.nav.budstikka.application.EffectuateDecision
import no.nav.budstikka.application.InboxMessageTask
import no.nav.budstikka.infrastructure.database.config.TransactionRunner
import no.nav.budstikka.infrastructure.database.delivery.DeliveryRepository
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import no.nav.budstikka.infrastructure.task.BaseTask
import no.nav.budstikka.infrastructure.task.config.TaskConfig

fun DependencyRegistry.taskModule() {
    provide<EffectuateDecision> {
        EffectuateDecision(
            transactionRunner = resolve<TransactionRunner>(),
            inboxMessageRepository = resolve<InboxMessageRepository>(),
            deliveryRepository = resolve<DeliveryRepository>(),
        )
    }
    provide<List<BaseTask>> {
        val taskConfig = resolve<TaskConfig>()
        listOf(
            InboxMessageTask(
                repository = resolve<InboxMessageRepository>(),
                effectuator = resolve<EffectuateDecision>(),
                config = taskConfig.inboxMessage,
            ),
        )
    }.cleanup { tasks ->
        tasks.forEach(AutoCloseable::close)
    }
}
