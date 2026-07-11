package no.nav.budstikka.application

import kotlinx.serialization.SerializationException
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.dispatchJson
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessage
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import no.nav.budstikka.infrastructure.task.BaseTask
import no.nav.budstikka.infrastructure.task.LeaseBudgetDrainer
import no.nav.budstikka.infrastructure.task.config.LeaseDrainConfig
import org.slf4j.LoggerFactory

/**
 * Beslutnings-workeren (#56): claimer mottatte inbox-meldinger (FOR UPDATE SKIP LOCKED + lease, ADR
 * 0004 — flere replicaer kan kjøre samtidig), dekoder payloaden og effektuerer utfallet per melding
 * via [EffectuateDecision] (delivery + inbox-status i én DB-tx).
 *
 * Beslutningen delegeres til [DecisionProcess], som ruter til policy per meldingstype og lar hver
 * policy hente sitt eget grunnlag (f.eks. PDL for isAlive-gaten).
 *
 * Lease-budsjett-draineringen deles med outbox-workeren via [LeaseBudgetDrainer]: workeren slutter å
 * starte nye meldinger når budsjettandelen av leasen er brukt, så en treg batch ikke krysser leasen
 * (og en peer re-enricher samme melding). Uberørte claimede meldinger blir stående til leasen utløper.
 */
class InboxMessageTask(
    private val repository: InboxMessageRepository,
    private val effectuator: EffectuateDecision,
    private val decisionProcess: DecisionProcess,
    private val drainer: LeaseBudgetDrainer,
    private val config: LeaseDrainConfig,
) : BaseTask(
        name = TASK_NAME,
        interval = config.interval,
    ) {
    private val logger = LoggerFactory.getLogger(InboxMessageTask::class.java)

    override suspend fun runIteration() {
        runOnce()
    }

    internal suspend fun runOnce() {
        drainer.drain(
            leaseDuration = config.leaseDuration,
            eventId = { it.eventId.toString() },
            claim = { repository.claim(config.batchSize, config.leaseDuration) },
            process = { message ->
                effectuator.effectuate(message.eventId, decideFor(message))
                logger.info("Message processed successfully")
            },
        )
    }

    private suspend fun decideFor(message: InboxMessage): Decision =

        try {
            logger.debug("Decoding inbox payload")
            val dispatch = dispatchJson.decodeFromString<Dispatch>(message.payload)
            decisionProcess.process(dispatch)
        } catch (error: SerializationException) {
            val reason = error.message ?: "Unable to decode inbox payload"
            logger.warn(
                "Unable to decode inbox payload eventId={}",
                message.eventId,
                error,
            )
            Decision.Failed(reason)
        }

    private companion object {
        const val TASK_NAME = "inbox-message-task"
    }
}
