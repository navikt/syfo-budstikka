package no.nav.budstikka.application

import kotlinx.serialization.SerializationException
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.dispatchJson
import no.nav.budstikka.infrastructure.config.MdcKeys
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessage
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import no.nav.budstikka.infrastructure.task.BaseTask
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Duration

class InboxMessageTask(
    private val repository: InboxMessageRepository,
    interval: Duration,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val onDispatch: suspend (Dispatch) -> Unit = {},
) : BaseTask(
        name = TASK_NAME,
        interval = interval,
    ) {
    private val logger = LoggerFactory.getLogger(InboxMessageTask::class.java)

    init {
        require(batchSize > 0) { "batchSize must be greater than 0" }
    }

    override suspend fun runIteration() {
        runOnce()
    }

    internal suspend fun runOnce() {
        repository.pollReceived(batchSize).forEach { message ->
            decode(message)?.let { dispatch -> onDispatch(dispatch) }
        }
    }

    private fun decode(message: InboxMessage): Dispatch? =
        MDC.putCloseable(MdcKeys.EVENT_ID, message.eventId.toString()).use {
            try {
                dispatchJson.decodeFromString<Dispatch>(message.payload)
            } catch (error: SerializationException) {
                logger.warn(
                    "Unable to decode inbox payload eventId={}",
                    message.eventId,
                    error,
                )
                null
            }
        }

    private companion object {
        const val TASK_NAME = "inbox-message-task"
        const val DEFAULT_BATCH_SIZE = 100
    }
}
