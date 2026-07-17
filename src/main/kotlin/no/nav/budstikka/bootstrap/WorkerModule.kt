package no.nav.budstikka.bootstrap

import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.BrevChannelHandler
import no.nav.budstikka.application.BrukervarselChannelHandler
import no.nav.budstikka.application.ChannelHandler
import no.nav.budstikka.application.DeliveryWorker
import no.nav.budstikka.application.EffectuateDecision
import no.nav.budstikka.application.InboxMessageWorker
import no.nav.budstikka.application.LeaseBudgetDrainer
import no.nav.budstikka.application.MicrofrontendChannelHandler
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.application.port.DocumentDistributor
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.MicrofrontendPublisher
import no.nav.budstikka.application.port.MinSideBrukervarselPublisher
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeathGate
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.decision.DecisionRule
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.infrastructure.worker.BackgroundLoop
import no.nav.budstikka.infrastructure.worker.config.WorkerConfig

fun DependencyRegistry.workerModule() {
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
            Channel.BRUKERVARSEL to BrukervarselChannelHandler(resolve<MinSideBrukervarselPublisher>()),
            Channel.MICROFRONTEND to MicrofrontendChannelHandler(resolve<MicrofrontendPublisher>()),
            Channel.BREV to BrevChannelHandler(resolve<DocumentDistributor>()),
        )
    }
    // Composition seam (jf. H3): application-workerne eier én runde (`runOnce`), infrastruktur-
    // løkka eier livssyklusen. Kun bootstrap ser begge lag.
    provide<List<BackgroundLoop>> {
        val workerConfig = resolve<WorkerConfig>()
        val metrics = resolve<DispatchMetrics>()
        val meterRegistry = resolve<PrometheusMeterRegistry>()
        val inboxMessageWorker =
            InboxMessageWorker(
                repository = resolve<InboxMessageRepository>(),
                effectuator = resolve<EffectuateDecision>(),
                decisionProcess = resolve<DecisionProcess>(),
                drainer =
                    LeaseBudgetDrainer(
                        leaseBudgetFraction = workerConfig.inboxMessage.leaseBudgetFraction,
                        maxConsecutiveItemFailures = workerConfig.inboxMessage.maxConsecutiveItemFailures,
                    ),
                config = workerConfig.inboxMessage,
                metrics = metrics,
            )
        val deliveryWorker =
            DeliveryWorker(
                repository = resolve<DeliveryRepository>(),
                handlers = resolve<Map<Channel, ChannelHandler>>(),
                drainer =
                    LeaseBudgetDrainer(
                        leaseBudgetFraction = workerConfig.delivery.leaseBudgetFraction,
                        maxConsecutiveItemFailures = workerConfig.delivery.maxConsecutiveItemFailures,
                    ),
                config = workerConfig.delivery,
                metrics = metrics,
            )
        listOf(
            BackgroundLoop(
                name = "inbox-message",
                interval = workerConfig.inboxMessage.interval,
                meterRegistry = meterRegistry,
                iteration = inboxMessageWorker::runOnce,
            ),
            BackgroundLoop(
                name = "delivery",
                interval = workerConfig.delivery.interval,
                meterRegistry = meterRegistry,
                iteration = deliveryWorker::runOnce,
            ),
        )
    }.cleanup { loops ->
        loops.forEach(AutoCloseable::close)
    }
}
