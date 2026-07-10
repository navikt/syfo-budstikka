package no.nav.budstikka.application

import kotlinx.serialization.SerializationException
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.dispatchJson
import no.nav.budstikka.infrastructure.config.MdcKeys
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessage
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import no.nav.budstikka.infrastructure.task.BaseTask
import no.nav.budstikka.infrastructure.task.config.InboxMessageTaskConfig
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class InboxMessageTask(
    private val repository: InboxMessageRepository,
    private val config: InboxMessageTaskConfig,
    private val onDispatch: suspend (Dispatch) -> Unit = {},
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
            when (val decodeResult = decode(message)) {
                is DecodeResult.Success -> {
                    repository.markProcessed(message.eventId)
                    onDispatch(decodeResult.dispatch)
                }

                is DecodeResult.Failure -> repository.markFailed(message.eventId, decodeResult.reason)
            }
        }
    }

    private fun decode(message: InboxMessage): DecodeResult =
        MDC.putCloseable(MdcKeys.EVENT_ID, message.eventId.toString()).use {
            try {
                logger.info("Decoding inbox payload")
                DecodeResult.Success(dispatchJson.decodeFromString<Dispatch>(message.payload))
            } catch (error: SerializationException) {
                val reason = error.message ?: "Unable to decode inbox payload"
                logger.warn(
                    "Unable to decode inbox payload eventId={}",
                    message.eventId,
                    error,
                )
                DecodeResult.Failure(reason)
            }
        }

    private sealed interface DecodeResult {
        data class Success(
            val dispatch: Dispatch,
        ) : DecodeResult

        data class Failure(
            val reason: String,
        ) : DecodeResult
    }

    private companion object {
        const val TASK_NAME = "inbox-message-task"
    }
}
