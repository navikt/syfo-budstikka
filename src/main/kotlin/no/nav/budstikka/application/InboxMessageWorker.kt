package no.nav.budstikka.application

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.dispatch.Dispatch
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

/**
 * Beslutnings-workeren (#56): claimer mottatte inbox-meldinger (FOR UPDATE SKIP LOCKED + lease, ADR
 * 0004 — flere replicaer kan kjøre samtidig), dekoder payloaden og effektuerer utfallet per melding
 * via [EffectuateDecision] (delivery + inbox-status i én DB-tx).
 *
 * Beslutningen delegeres til [DecisionProcess], som ruter til policy per meldingstype og lar hver
 * policy hente sitt eget grunnlag (f.eks. PDL for isAlive-gaten).
 *
 * Workeren eier én runde ([runOnce]); selve løkke-livssyklusen (intervall, heartbeat, shutdown)
 * komponeres rundt den i bootstrap via `BackgroundLoop`. Lease-budsjett-draineringen deles med
 * outbox-workeren via [LeaseBudgetDrainer]: workeren slutter å starte nye meldinger når
 * budsjettandelen av leasen er brukt, så en treg batch ikke krysser leasen (og en peer re-enricher
 * samme melding). Uberørte claimede meldinger blir stående til leasen utløper.
 */
class InboxMessageWorker(
    private val repository: InboxMessageRepository,
    private val effectuator: EffectuateDecision,
    private val decisionProcess: DecisionProcess,
    private val drainer: LeaseBudgetDrainer,
    private val config: LeaseDrainConfig,
    private val metrics: DispatchMetrics,
) {
    private val logger = LoggerFactory.getLogger(InboxMessageWorker::class.java)

    suspend fun runOnce() {
        drainer.drain(
            leaseDuration = config.leaseDuration,
            eventId = { it.eventId.toString() },
            claim = {
                repository.claim(config.batchSize, config.leaseDuration, config.maxAttempts).also { claimed ->
                    if (claimed.isEmpty()) metrics.inboxEmptyPoll() else metrics.inboxClaimed(claimed.size)
                }
            },
            process = { message -> processClaimed(message) },
        )
    }

    /**
     * `reference` på MDC korrelerer OPPRETT↔FERDIGSTILL i Loki (delt reference, ulik eventId, B59);
     * `withContext(MDCContext())` bevarer feltet over suspensjon i decisionProcess/effektuering.
     */
    private suspend fun processClaimed(message: InboxMessage) {
        val dispatch = Dispatch(reference = message.reference, content = message.content)
        MDC.putCloseable(MdcKeys.REFERENCE, message.reference).use {
            withContext(MDCContext()) {
                completeDecision(message.eventId, decisionProcess.process(dispatch))
            }
        }
    }

    private suspend fun completeDecision(
        eventId: UUID,
        decision: Decision,
    ) {
        effectuator.effectuate(eventId, decision)
        metrics.record(decision)
        val fields = decision.logFields()
        logger.info(
            "Inbox message processed".withPlaceholders(fields),
            *fields.toTypedArray(),
        )
    }

    private fun DispatchMetrics.record(decision: Decision) {
        when (decision) {
            is Decision.Processed -> inboxProcessed()
            is Decision.Dropped -> inboxDropped(decision.reason)
            is Decision.Failed -> inboxFailed()
        }
    }

    private fun Decision.logFields(): List<StructuredArgument> =
        when (this) {
            is Decision.Processed ->
                listOf(
                    kv("result", "PROCESSED"),
                    kv("deliveryCount", deliveries.size),
                )

            is Decision.Dropped ->
                listOf(
                    kv("result", "DROPPED"),
                    kv("dropReason", reason.name),
                )

            is Decision.Failed ->
                listOf(
                    kv("result", "FAILED"),
                    kv("failureReason", errorMessage),
                )
        }

    private fun String.withPlaceholders(fields: List<StructuredArgument>): String = this + " {}".repeat(fields.size)
}
