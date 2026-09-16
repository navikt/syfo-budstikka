package no.nav.budstikka.application.worker

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.esyfo.observability.Event

internal object LeaseWorkerLogEvents {
    val claimedRowProcessingFailed =
        Event<ClaimedRowFailureContext>(
            name = "worker.claimed_row.failed",
            level = ApplicationLogLevel.WARN,
            message = "Failed processing claimed row; continuing with next row",
            operation = "worker.process_claimed_row",
            errorCode = "CLAIMED_ROW_PROCESSING_FAILED",
            fields = claimedRowFailureFields(),
        )

    val batchDrainAborted =
        Event<ClaimedRowFailureContext>(
            name = "worker.batch_drain.aborted",
            level = ApplicationLogLevel.ERROR,
            message = "Aborting batch drain after consecutive item failures; treating this as a systemic failure",
            operation = "worker.drain_batch",
            errorCode = "CONSECUTIVE_ITEM_FAILURES",
            fields = claimedRowFailureFields(),
        )

    val leaseBudgetExhausted =
        Event<LeaseBudgetContext>(
            name = "worker.lease_budget.exhausted",
            level = ApplicationLogLevel.WARN,
            message =
                "Stopping batch drain because the lease budget is spent; unprocessed rows keep their lease so " +
                    "a later poll reclaims them. Recurring hits mean batchSize is too high or downstream is too slow",
            operation = "worker.drain_batch",
            errorCode = "LEASE_BUDGET_EXHAUSTED",
            fields =
                mapOf(
                    MdcKeys.LEASE_BUDGET_FRACTION to { it.leaseBudgetFraction },
                    MdcKeys.UNPROCESSED_ROWS_COUNT to { it.unprocessedRowsCount },
                    MdcKeys.CLAIMED_ROWS_COUNT to { it.claimedRowsCount },
                ),
        )

    private fun claimedRowFailureFields(): Map<String, (ClaimedRowFailureContext) -> Any?> =
        mapOf(
            MdcKeys.CONSECUTIVE_ITEM_FAILURE_COUNT to { it.consecutiveItemFailureCount },
            MdcKeys.MAX_ITEM_FAILURE_COUNT to { it.maxItemFailureCount },
            MdcKeys.ERROR_TYPE to { it.errorType },
            MdcKeys.CAUSE_TYPE to { it.causeType },
            MdcKeys.DELIVERY_ID to { it.itemFields[MdcKeys.DELIVERY_ID] },
            MdcKeys.DELIVERY_CHANNEL to { it.itemFields[MdcKeys.DELIVERY_CHANNEL] },
            MdcKeys.REFERENCE to { it.itemFields[MdcKeys.REFERENCE] },
            MdcKeys.HANDLER to { it.itemFields[MdcKeys.HANDLER] },
        )
}

internal data class ClaimedRowFailureContext(
    val consecutiveItemFailureCount: Int,
    val maxItemFailureCount: Int,
    val errorType: String,
    val causeType: String?,
    val itemFields: Map<String, Any?>,
)

internal data class LeaseBudgetContext(
    val leaseBudgetFraction: Int,
    val unprocessedRowsCount: Int,
    val claimedRowsCount: Int,
)
