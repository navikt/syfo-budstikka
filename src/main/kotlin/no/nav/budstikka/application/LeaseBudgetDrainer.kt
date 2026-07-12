package no.nav.budstikka.application

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Delt maskineri for claim-lease-workerne (inbox og delivery): claim en bunke, prosesser hver rad
 * innenfor en andel [leaseBudgetFraction] av leasen, og stopp å starte nye rader når budsjettet er
 * brukt. Uberørte claimede rader blir stående til leasen utløper og plukkes opp av en senere poll
 * (evt. av en peer, ADR 0004). Hver rad prosesseres med sin eventId i MDC for korrelasjon.
 *
 * Cleanup-workeren (B42) bruker IKKE denne — den er en advisory-lock-gatet batch-DELETE, en annen form.
 */
class LeaseBudgetDrainer(
    private val leaseBudgetFraction: Double,
) {
    private val logger = LoggerFactory.getLogger(LeaseBudgetDrainer::class.java)

    init {
        require(leaseBudgetFraction > 0.0 && leaseBudgetFraction <= 1.0) {
            "leaseBudgetFraction must be in (0.0, 1.0]"
        }
    }

    suspend fun <T> drain(
        leaseDuration: Duration,
        eventId: (T) -> String?,
        claim: suspend () -> List<T>,
        process: suspend (T) -> Unit,
    ) {
        val startedAt = Clock.System.now()
        val budget = (leaseDuration.toMillis() * leaseBudgetFraction).toLong().milliseconds
        val claimed = claim()
        for ((index, item) in claimed.withIndex()) {
            if (Clock.System.now() - startedAt >= budget) {
                logger.warn(
                    "Stopping batch drain at {}% of lease budget with {} of {} claimed row(s) unprocessed; their lease expires so a later poll reclaims them. Recurring hits mean batchSize is too high or downstream is too slow.",
                    (leaseBudgetFraction * 100).toInt(),
                    claimed.size - index,
                    claimed.size,
                )
                break
            }
            val closeable = eventId(item)?.let { MDC.putCloseable(MdcKeys.EVENT_ID, it) }
            closeable.use { _ ->
                withContext(MDCContext()) {
                    process(item)
                }
            }
        }
    }
}
