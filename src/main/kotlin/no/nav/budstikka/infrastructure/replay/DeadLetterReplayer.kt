package no.nav.budstikka.infrastructure.replay

import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.kafka.consumer.ParseResult
import no.nav.budstikka.infrastructure.kafka.consumer.parseDispatch

data class ReplayResult(
    val replayed: Int,
    val skipped: Int,
)

class DeadLetterReplayer(
    private val deadLetterMessageRepository: DeadLetterMessageRepository,
    private val inboxMessageRepository: InboxMessageRepository,
) {
    private val logger = applicationLogger(DeadLetterReplayer::class.java)

    suspend fun replay(limit: Int): ReplayResult {
        var offset = 0L
        var replayed = 0
        var skipped = 0

        repeat(MAX_BATCHES) {
            val replayable = deadLetterMessageRepository.findReplayable(limit, offset)
            val parsed =
                replayable.mapNotNull { deadLetter ->
                    when (val result = parseDispatch(deadLetter.payload)) {
                        is ParseResult.Success ->
                            deadLetter.id to
                                InboxMessage(
                                    eventId = deadLetter.eventId,
                                    reference = result.dispatch.reference,
                                    content = result.dispatch.content,
                                )

                        ParseResult.Failure -> {
                            logger.event(
                                ReplayLogEvents.rowSkipped,
                                ReplayRowContext(deadLetter.eventId.toString()),
                            )
                            null
                        }
                    }
                }
            if (parsed.isNotEmpty()) {
                inboxMessageRepository.saveBatch(parsed.map { it.second })
                deadLetterMessageRepository.deleteByIds(parsed.map { it.first })
            }

            val skippedInBatch = replayable.size - parsed.size
            replayed += parsed.size
            skipped += skippedInBatch
            offset += skippedInBatch

            if (replayable.size < limit) {
                return ReplayResult(replayed = replayed, skipped = skipped)
            }
        }

        logger.event(
            ReplayLogEvents.batchLimitReached,
            ReplayBatchContext(
                maxBatches = MAX_BATCHES,
                replayedCount = replayed,
                skippedCount = skipped,
            ),
        )
        return ReplayResult(replayed = replayed, skipped = skipped)
    }

    private companion object {
        const val MAX_BATCHES = 1000
    }
}
