package no.nav.budstikka.application

import kotlinx.serialization.SerializationException
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.dispatchJson
import no.nav.budstikka.infrastructure.config.MdcKeys
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessage
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import no.nav.budstikka.infrastructure.task.BaseTask
import no.nav.budstikka.infrastructure.task.config.InboxMessageTaskConfig
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Beslutnings-workeren (#56): poller mottatte inbox-meldinger, dekoder payloaden og effektuerer
 * utfallet per melding via [EffectuateDecision] (delivery + inbox-status i én DB-tx).
 *
 * Grunnlagsinnhentingen (PDL/KRR) og den rene [no.nav.budstikka.domain.decision.decide]-rutingen er
 * ennå IKKE koblet inn — [decideFor] er en placeholder som markerer en gyldig payload som behandlet
 * uten leveranser. Når enrichment lander, byttes placeholderen mot `DecisionProcess.process`; den
 * atomiske effektueringen står allerede klar.
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
        repository.pollReceived(config.batchSize).forEach { message ->
            effectuator.effectuate(message.eventId, decideFor(message))
        }
    }

    private fun decideFor(message: InboxMessage): Decision =
        MDC.putCloseable(MdcKeys.EVENT_ID, message.eventId.toString()).use {
            try {
                logger.info("Decoding inbox payload")
                val dispatch = dispatchJson.decodeFromString<Dispatch>(message.payload)
                placeholderDecision(dispatch)
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

    // Placeholder til enrichment/decide er koblet inn: en gyldig payload gir ingen leveranser ennå.
    @Suppress("UNUSED_PARAMETER")
    private fun placeholderDecision(dispatch: Dispatch): Decision = Decision.Processed(emptyList())

    private companion object {
        const val TASK_NAME = "inbox-message-task"
    }
}
