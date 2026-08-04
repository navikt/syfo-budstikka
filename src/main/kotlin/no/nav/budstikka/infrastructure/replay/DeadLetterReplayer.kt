package no.nav.budstikka.infrastructure.replay

import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.MdcKeys
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.kafka.consumer.ParseResult
import no.nav.budstikka.infrastructure.kafka.consumer.parseDispatch
import org.slf4j.LoggerFactory

data class ReplayResult(
    val replayed: Int,
    val skipped: Int,
)

class DeadLetterReplayer(
    private val deadLetterMessageRepository: DeadLetterMessageRepository,
    private val inboxMessageRepository: InboxMessageRepository,
) {
    private val logger = LoggerFactory.getLogger(DeadLetterReplayer::class.java)

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
                            logger.warn(
                                "Dead-letter replay skipped unparseable row {}",
                                kv(MdcKeys.EVENT_ID, deadLetter.eventId),
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

        logger.warn(
            "Dead-letter replay stopped after reaching batch limit {} {} {}",
            kv(MdcKeys.MAX_BATCHES, MAX_BATCHES),
            kv(MdcKeys.REPLAYED_COUNT, replayed),
            kv(MdcKeys.SKIPPED_COUNT, skipped),
        )
        return ReplayResult(replayed = replayed, skipped = skipped)
    }

    private companion object {
        const val MAX_BATCHES = 1000
    }
}
