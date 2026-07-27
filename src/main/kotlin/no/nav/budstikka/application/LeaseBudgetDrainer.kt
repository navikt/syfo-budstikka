package no.nav.budstikka.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Shared machinery for claim-lease workers (inbox and delivery): claim a batch, process each row
 * within [leaseBudgetFraction] of the lease, and stop starting rows once the budget is spent.
 * Untouched claimed rows remain until lease expiry and a later poll (or a peer, ADR 0004) picks them
 * up. Each row is processed with its eventId in MDC for correlation. Item-specific failures are
 * isolated; the drainer stops only after [maxConsecutiveItemFailures] consecutive failures (a
 * systemic-failure heuristic).
 */
class LeaseBudgetDrainer(
    private val leaseBudgetFraction: Double,
    private val maxConsecutiveItemFailures: Int,
    private val clock: Clock = Clock.System,
) {
    private val logger = LoggerFactory.getLogger(LeaseBudgetDrainer::class.java)

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
        failureFields: (T) -> List<StructuredArgument> = { emptyList() },
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

    /**
     * Processes one claimed row with its eventId in MDC. On success the counter resets (returns 0).
     * An item-specific failure is isolated (logged and counted) so the next row can continue, unless
     * [maxConsecutiveItemFailures] is reached; then the failure is rethrown as systemic. Logging is
     * inside the MDC scope so entries carry [MdcKeys.EVENT_ID]. Returns the new consecutive count.
     */
    private suspend fun <T> processItem(
        item: T,
        eventId: (T) -> String?,
        failureFields: (T) -> List<StructuredArgument>,
        process: suspend (T) -> Unit,
        consecutiveItemFailures: Int,
    ): Int {
        val closeable = eventId(item)?.let { MDC.putCloseable(MdcKeys.EVENT_ID, it) }
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
                    val fields =
                        buildList {
                            add(kv(MdcKeys.CONSECUTIVE_ITEM_FAILURE_COUNT, failures))
                            add(kv(MdcKeys.MAX_ITEM_FAILURE_COUNT, maxConsecutiveItemFailures))
                            add(kv(MdcKeys.ERROR_TYPE, error.javaClass.simpleName))
                            error.cause?.let { add(kv(MdcKeys.CAUSE_TYPE, it.javaClass.simpleName)) }
                            addAll(failureFields(item))
                        }
                    logger.warn(
                        withPlaceholders("Failed processing claimed row; continuing with next row", fields),
                        *fields.toTypedArray(),
                    )
                    if (failures >= maxConsecutiveItemFailures) {
                        logger.error(
                            withPlaceholders(
                                "Aborting batch drain after consecutive item failures; treating this as a systemic failure",
                                fields,
                            ),
                            *(fields + error).toTypedArray(),
                        )
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
        logger.warn(
            "Stopping batch drain because the lease budget is spent; unprocessed rows keep their lease so a later poll reclaims them. Recurring hits mean batchSize is too high or downstream is too slow {} {} {}",
            kv(MdcKeys.LEASE_BUDGET_FRACTION, (leaseBudgetFraction * 100).toInt()),
            kv(MdcKeys.UNPROCESSED_ROWS_COUNT, unprocessed),
            kv(MdcKeys.CLAIMED_ROWS_COUNT, total),
        )
    }
}
