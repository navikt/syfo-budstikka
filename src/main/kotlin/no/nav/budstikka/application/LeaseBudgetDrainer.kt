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
 * Delt maskineri for claim-lease-workerne (inbox og delivery): claim en bunke, prosesser hver rad
 * innenfor en andel [leaseBudgetFraction] av leasen, og stopp å starte nye rader når budsjettet er
 * brukt. Uberørte claimede rader blir stående til leasen utløper og plukkes opp av en senere poll
 * (evt. av en peer, ADR 0004). Hver rad prosesseres med sin eventId i MDC for korrelasjon.
 * Radspesifikke feil isoleres per item; draineren avbryter først etter
 * [maxConsecutiveItemFailures] på rad (systemisk-feil-heuristikk). *
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
     * Prosesserer én claimet rad med sin eventId i MDC. Ved suksess nullstilles telleren (retur 0);
     * ved radspesifikk feil isoleres den (logges og telleren økes) slik at neste rad kan fortsette,
     * med mindre [maxConsecutiveItemFailures] er nådd – da rethrowes feilen som systemisk. Loggingen
     * skjer inne i MDC-scopet slik at linjene bærer [MdcKeys.EVENT_ID]. Returnerer nytt antall feil
     * på rad.
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
                            add(kv("consecutiveItemFailures", failures))
                            add(kv("maxItemFailures", maxConsecutiveItemFailures))
                            add(kv("errorType", error.javaClass.simpleName))
                            error.cause?.let { add(kv("causeType", it.javaClass.simpleName)) }
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
            kv("leaseBudgetPercent", (leaseBudgetFraction * 100).toInt()),
            kv("unprocessedRows", unprocessed),
            kv("claimedRows", total),
        )
    }
}
