package no.nav.budstikka.bootstrap

import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.delivery.ArbeidsgivervarselChannelHandler
import no.nav.budstikka.application.delivery.BrevChannelHandler
import no.nav.budstikka.application.delivery.BrukervarselChannelHandler
import no.nav.budstikka.application.delivery.ChannelHandler
import no.nav.budstikka.application.delivery.DeliveryMetrics
import no.nav.budstikka.application.delivery.DeliveryWorker
import no.nav.budstikka.application.delivery.DocumentDistributor
import no.nav.budstikka.application.delivery.LedervarselChannelHandler
import no.nav.budstikka.application.delivery.LedervarselPublisher
import no.nav.budstikka.application.delivery.MicrofrontendChannelHandler
import no.nav.budstikka.application.delivery.MicrofrontendPublisher
import no.nav.budstikka.application.delivery.MinSideBrukervarselPublisher
import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.inbox.EffectuateDecision
import no.nav.budstikka.application.inbox.InboxMessageWorker
import no.nav.budstikka.application.inbox.InboxMetrics
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.application.retention.RetentionMetrics
import no.nav.budstikka.application.retention.RetentionRepository
import no.nav.budstikka.application.worker.LeaseBudgetDrainer
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.decision.DecisionRule
import no.nav.budstikka.infrastructure.worker.BackgroundLoop
import no.nav.budstikka.infrastructure.worker.config.WorkerConfig
import no.nav.budstikka.infrastructure.worker.retention.RetentionWorker

fun DependencyRegistry.workerModule() {
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
            Channel.LEDERVARSEL to LedervarselChannelHandler(resolve<LedervarselPublisher>()),
            Channel.MICROFRONTEND to MicrofrontendChannelHandler(resolve<MicrofrontendPublisher>()),
            Channel.BREV to BrevChannelHandler(resolve<DocumentDistributor>()),
            Channel.ARBEIDSGIVERVARSEL to
                ArbeidsgivervarselChannelHandler(
                    publisher = resolve<ArbeidsgiverNotificationPublisher>(),
                    narmesteLederLookup = resolve<NarmesteLederLookup>(),
                    metrics = resolve<DeliveryMetrics>(),
                ),
        )
    }
    provide<List<BackgroundLoop>> {
        val workerConfig = resolve<WorkerConfig>()
        val inboxMetrics = resolve<InboxMetrics>()
        val deliveryMetrics = resolve<DeliveryMetrics>()
        val retentionMetrics = resolve<RetentionMetrics>()
        val meterRegistry = resolve<PrometheusMeterRegistry>()
        val retentionWorker =
            RetentionWorker(
                cleanup = resolve<RetentionRepository>(),
                batchSize = workerConfig.retentionCleanup.batchSize,
                metrics = retentionMetrics,
            )
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
                metrics = inboxMetrics,
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
                metrics = deliveryMetrics,
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
            BackgroundLoop(
                name = "retention-cleanup",
                interval = workerConfig.retentionCleanup.interval,
                meterRegistry = meterRegistry,
                iteration = retentionWorker::runOnce,
            ),
        )
    }.cleanup { loops ->
        loops.forEach(AutoCloseable::close)
    }
}
