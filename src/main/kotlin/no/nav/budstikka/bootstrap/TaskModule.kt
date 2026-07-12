package no.nav.budstikka.bootstrap

import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import no.nav.budstikka.application.ChannelHandler
import no.nav.budstikka.application.DeliveryTask
import no.nav.budstikka.application.EffectuateDecision
import no.nav.budstikka.application.InboxMessageTask
import no.nav.budstikka.application.MicrofrontendChannelHandler
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeathGate
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.decision.DecisionRule
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.infrastructure.database.config.TransactionRunner
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import no.nav.budstikka.infrastructure.foundation.NoopDeathLookup
import no.nav.budstikka.infrastructure.kafka.producer.MicrofrontendPublisher
import no.nav.budstikka.infrastructure.task.BaseTask
import no.nav.budstikka.infrastructure.task.LeaseBudgetDrainer
import no.nav.budstikka.infrastructure.task.config.TaskConfig

fun DependencyRegistry.taskModule() {
    provide<DeathLookup> { NoopDeathLookup() }
    provide<List<DecisionRule>> { listOf(DeathGate(resolve<DeathLookup>())) }
    provide<DecisionProcess> { DecisionProcess(resolve<List<DecisionRule>>()) }
    provide<EffectuateDecision> {
        EffectuateDecision(
            transactionRunner = resolve<TransactionRunner>(),
            inboxMessageRepository = resolve<InboxMessageRepository>(),
            deliveryRepository = resolve<DeliveryRepository>(),
        )
    }
    provide<Map<Channel, ChannelHandler>> {
        mapOf(
            Channel.MICROFRONTEND to MicrofrontendChannelHandler(resolve<MicrofrontendPublisher>()),
        )
    }
    provide<List<BaseTask>> {
        val taskConfig = resolve<TaskConfig>()
        listOf(
            InboxMessageTask(
                repository = resolve<InboxMessageRepository>(),
                effectuator = resolve<EffectuateDecision>(),
                decisionProcess = resolve<DecisionProcess>(),
                drainer = LeaseBudgetDrainer(taskConfig.inboxMessage.leaseBudgetFraction),
                config = taskConfig.inboxMessage,
            ),
            DeliveryTask(
                repository = resolve<DeliveryRepository>(),
                handlers = resolve<Map<Channel, ChannelHandler>>(),
                drainer = LeaseBudgetDrainer(taskConfig.delivery.leaseBudgetFraction),
                config = taskConfig.delivery,
            ),
        )
    }.cleanup { tasks ->
        tasks.forEach(AutoCloseable::close)
    }
}
