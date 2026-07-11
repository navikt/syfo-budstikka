package no.nav.budstikka.application

import kotlinx.serialization.SerializationException
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionFoundation
import no.nav.budstikka.domain.decision.decide
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.dispatchJson
import no.nav.budstikka.infrastructure.config.MdcKeys
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessage
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import no.nav.budstikka.infrastructure.task.BaseTask
import no.nav.budstikka.infrastructure.task.config.InboxMessageTaskConfig
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Beslutnings-workeren (#56): claimer mottatte inbox-meldinger (FOR UPDATE SKIP LOCKED + lease, ADR
 * 0004 — flere replicaer kan kjøre samtidig), dekoder payloaden og effektuerer utfallet per melding
 * via [EffectuateDecision] (delivery + inbox-status i én DB-tx).
 *
 * Den rene [decide]-rutingen er koblet inn, men grunnlagsinnhenting (PDL/KRR/NL) er ikke aktiv ennå.
 * Derfor evalueres beslutningen foreløpig med tomt [DecisionFoundation]-grunnlag; flere gates kobles
 * inn additivt når de respektive lookup-adapterne lander.
 *
 * For å unngå at en treg batch krysser leasen (og dermed at en peer re-enricher samme melding), stopper
 * [runOnce] å starte nye meldinger når [InboxMessageTaskConfig.leaseBudgetFraction] av leasen er brukt.
 * Uberørte claimede meldinger blir stående til leasen utløper og plukkes opp av en senere poll.
 */
class InboxMessageTask(
    private val repository: InboxMessageRepository,
    private val effectuator: EffectuateDecision,
    private val config: InboxMessageTaskConfig,
) : BaseTask(
        name = TASK_NAME,
        interval = config.interval,
    ) {
    private val logger = LoggerFactory.getLogger(InboxMessageTask::class.java)

    override suspend fun runIteration() {
        runOnce()
    }

    internal suspend fun runOnce() {
        this.name
        val startedAt = Clock.System.now()
        val budget = (config.leaseDuration.toMillis() * config.leaseBudgetFraction).toLong().milliseconds
        val claimed = repository.claim(config.batchSize, config.leaseDuration)
        for ((index, message) in claimed.withIndex()) {
            if (Clock.System.now() - startedAt >= budget) {
                logger.warn(
                    "Stopping batch drain at {}% of lease budget with {} of {} claimed message(s) unprocessed; " +
                        "their lease expires so a later poll reclaims them. Recurring hits mean batchSize is too " +
                        "high or enrichment/downstream is too slow.",
                    (config.leaseBudgetFraction * 100).toInt(),
                    claimed.size - index,
                    claimed.size,
                )
                break
            }
            effectuator.effectuate(message.eventId, decideFor(message))
        }
    }

    private fun decideFor(message: InboxMessage): Decision =
        MDC.putCloseable(MdcKeys.EVENT_ID, message.eventId.toString()).use {
            try {
                logger.info("Decoding inbox payload")
                val dispatch = dispatchJson.decodeFromString<Dispatch>(message.payload)
                decide(dispatch, DecisionFoundation())
            } catch (error: SerializationException) {
                val reason = error.message ?: "Unable to decode inbox payload"
                logger.warn(
                    "Unable to decode inbox payload eventId={}",
                    message.eventId,
                    error,
                )
                Decision.Failed(reason)
            }
        }

    private companion object {
        const val TASK_NAME = "inbox-message-task"
    }
}
