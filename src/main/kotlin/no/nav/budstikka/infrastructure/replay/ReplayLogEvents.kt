package no.nav.budstikka.infrastructure.replay

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.esyfo.observability.Event

internal object ReplayLogEvents {
    val rowSkipped =
        Event<ReplayRowContext>(
            name = "dead_letter_replay.row.skipped",
            level = ApplicationLogLevel.WARN,
            message = "Dead-letter replay skipped unparseable row",
            operation = "dead_letter_replay.parse",
            errorCode = "UNPARSEABLE_DEAD_LETTER",
            fields = mapOf(MdcKeys.EVENT_ID to { it.eventId }),
        )

    val batchLimitReached =
        Event<ReplayBatchContext>(
            name = "dead_letter_replay.batch_limit_reached",
            level = ApplicationLogLevel.WARN,
            message = "Dead-letter replay stopped after reaching batch limit",
            operation = "dead_letter_replay.replay",
            errorCode = "REPLAY_BATCH_LIMIT_REACHED",
            fields =
                mapOf(
                    MdcKeys.MAX_BATCHES to { it.maxBatches },
                    MdcKeys.REPLAYED_COUNT to { it.replayedCount },
                    MdcKeys.SKIPPED_COUNT to { it.skippedCount },
                ),
        )
}

internal data class ReplayRowContext(
    val eventId: String,
)

internal data class ReplayBatchContext(
    val maxBatches: Int,
    val replayedCount: Int,
    val skippedCount: Int,
)
