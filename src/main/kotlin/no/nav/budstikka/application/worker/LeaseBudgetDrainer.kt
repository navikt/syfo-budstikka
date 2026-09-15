package no.nav.budstikka.application.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import no.nav.budstikka.application.logging.ApplicationMdc
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.logging.applicationLogger
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Shared machinery for claim-lease workers (inbox and delivery): claim a batch, process each row
 * within [leaseBudgetFraction] of the lease, and stop starting rows once the budget is spent.
 * Untouched claimed rows remain until lease expiry and a later poll picks them up. Each row is
 * processed with its eventId in MDC for correlation. Item-specific failures are
 * isolated; the drainer stops only after [maxConsecutiveItemFailures] consecutive failures (a
 * systemic-failure heuristic).
 */
class LeaseBudgetDrainer(
    private val leaseBudgetFraction: Double,
    private val maxConsecutiveItemFailures: Int,
    private val clock: Clock = Clock.System,
) {
    private val logger = applicationLogger(LeaseBudgetDrainer::class.java)

    init {
        require(leaseBudgetFraction > 0.0 && leaseBudgetFraction <= 1.0) {
            "leaseBudgetFraction must be in (0.0, 1.0]"
        }
        require(maxConsecutiveItemFailures > 0) {
            "maxConsecutiveItemFailures must be greater than 0"
        }
    }

    suspend fun <T> drain(
        leaseDuration: Duration,
        eventId: (T) -> String?,
        failureFields: (T) -> Map<String, Any?> = { emptyMap() },
        claim: suspend () -> List<T>,
        process: suspend (T) -> Unit,
    ) {
        val startedAt = clock.now()
        val budget = leaseDuration * leaseBudgetFraction
        val claimed = claim()
        var consecutiveItemFailures = 0
        for ((index, item) in claimed.withIndex()) {
            if (clock.now() - startedAt >= budget) {
                logBudgetExhausted(unprocessed = claimed.size - index, total = claimed.size)
                break
            }
            consecutiveItemFailures = processItem(item, eventId, failureFields, process, consecutiveItemFailures)
        }
    }

    private suspend fun <T> processItem(
        item: T,
        eventId: (T) -> String?,
        failureFields: (T) -> Map<String, Any?>,
        process: suspend (T) -> Unit,
        consecutiveItemFailures: Int,
    ): Int {
        val closeable = eventId(item)?.let { ApplicationMdc.putCloseable(MdcKeys.EVENT_ID, it) }
        return closeable.use { _ ->
            withContext(MDCContext()) {
                try {
                    process(item)
                    0
                } catch (error: CancellationException) {
                    throw error
                } catch (error: AlreadyLoggedWorkerFailure) {
                    throw error
                } catch (error: Exception) {
                    val failures = consecutiveItemFailures + 1
                    val context =
                        ClaimedRowFailureContext(
                            consecutiveItemFailureCount = failures,
                            maxItemFailureCount = maxConsecutiveItemFailures,
                            errorType = error.javaClass.simpleName,
                            causeType = error.cause?.javaClass?.simpleName,
                            itemFields = failureFields(item),
                        )
                    logger.event(
                        LeaseWorkerLogEvents.claimedRowProcessingFailed,
                        context,
                    )
                    if (failures >= maxConsecutiveItemFailures) {
                        logger.event(LeaseWorkerLogEvents.batchDrainAborted, context, error)
                        throw AlreadyLoggedWorkerFailure(error)
                    }
                    failures
                }
            }
        }
    }

    private fun logBudgetExhausted(
        unprocessed: Int,
        total: Int,
    ) {
        logger.event(
            LeaseWorkerLogEvents.leaseBudgetExhausted,
            LeaseBudgetContext(
                leaseBudgetFraction = (leaseBudgetFraction * 100).toInt(),
                unprocessedRowsCount = unprocessed,
                claimedRowsCount = total,
            ),
        )
    }
}
