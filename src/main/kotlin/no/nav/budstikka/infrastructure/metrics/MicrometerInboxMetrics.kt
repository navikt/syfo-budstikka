package no.nav.budstikka.infrastructure.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.budstikka.application.inbox.InboxMetrics
import no.nav.budstikka.domain.decision.DropReason

/**
 * Micrometer adapter for [InboxMetrics]. Counts domain events in the shared registry;
 * names follow the Prometheus convention (Micrometer dot names → `snake_case`, counters gain `_total`):
 *
 * - `inbox_message_claimed_total`, `inbox_message_empty_polls_total`,
 *   `inbox_message_processed_total`, `inbox_message_dropped_total{reason}`,
 *   `inbox_message_failed_total`
 *
 * Labels are low-cardinality and PII-free. Claim and empty-poll counters are recorded at poll time;
 * decision outcomes are recorded only after a successful state transition. A process crash between
 * the database commit and counter increment can still undercount, so these metrics are observability
 * signals rather than an accounting source.
 */
class MicrometerInboxMetrics(
    private val registry: MeterRegistry,
) : InboxMetrics {
    private val claimedCounter = counter(INBOX_MESSAGE_CLAIMED)
    private val emptyPollsCounter = counter(INBOX_MESSAGE_EMPTY_POLLS)
    private val processedCounter = counter(INBOX_MESSAGE_PROCESSED)
    private val failedCounter = counter(INBOX_MESSAGE_FAILED)
    private val outsideSendingWindowCounter = counter(INBOX_OUTSIDE_SENDING_WINDOW)

    override fun claimed(count: Int) = claimedCounter.increment(count.toDouble())

    override fun emptyPoll() = emptyPollsCounter.increment()

    override fun processed() = processedCounter.increment()

    override fun dropped(reason: DropReason) =
        Counter
            .builder(INBOX_MESSAGE_DROPPED)
            .tag(TAG_REASON, reason.name.lowercase())
            .register(registry)
            .increment()

    override fun failed() = failedCounter.increment()

    override fun outsideSendingWindow(reason: String) = outsideSendingWindowCounter.increment()

    private fun counter(name: String): Counter = Counter.builder(name).register(registry)

    /**
     * Meter names (Micrometer dot form) and label keys in one source of truth, so the adapter and
     * tests reference the same string. The Prometheus registry converts dots to `_` and adds `_total`
     * during scraping; [MeterRegistry.get] lookup uses the dot form here.
     */
    companion object {
        const val INBOX_MESSAGE_CLAIMED = "inbox.message.claimed"
        const val INBOX_MESSAGE_EMPTY_POLLS = "inbox.message.empty.polls"
        const val INBOX_MESSAGE_PROCESSED = "inbox.message.processed"
        const val INBOX_MESSAGE_DROPPED = "inbox.message.dropped"
        const val INBOX_MESSAGE_FAILED = "inbox.message.failed"
        const val INBOX_OUTSIDE_SENDING_WINDOW = "inbox.outside.sending.window"

        const val TAG_REASON = "reason"
    }
}
