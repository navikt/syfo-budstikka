package no.nav.budstikka.infrastructure.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.application.port.NarmesteLederMissingReason
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DropReason

/**
 * Micrometer adapter for [DispatchMetrics]. Counts domain events in the shared registry;
 * names follow the Prometheus convention (Micrometer dot names → `snake_case`, counters gain `_total`):
 *
 * - `inbox_message_claimed_total`, `inbox_message_empty_polls_total`,
 *   `inbox_message_processed_total`, `inbox_message_dropped_total{reason}`,
 *   `inbox_message_failed_total`
 * - `delivery_claimed_total`, `delivery_empty_polls_total`, `delivery_total{channel,result}`
 * - `narmeste_leder_missing_total{reason}`
 *
 * Labels are low-cardinality and PII-free: lowercase [Channel] names and fixed outcomes. Counting
 * happens before the final state transition is guaranteed, so these metrics are observability
 * signals rather than an accounting source.
 */
class MicrometerDispatchMetrics(
    private val registry: MeterRegistry,
) : DispatchMetrics {
    private val inboxClaimed = counter(INBOX_MESSAGE_CLAIMED)
    private val inboxEmptyPolls = counter(INBOX_MESSAGE_EMPTY_POLLS)
    private val inboxProcessed = counter(INBOX_MESSAGE_PROCESSED)
    private val inboxFailed = counter(INBOX_MESSAGE_FAILED)
    private val inboxOutsideSendingWindow = counter(INBOX_OUTSIDE_SENDING_WINDOW)
    private val ferdigstillWithoutMatch = counter(FERDIGSTILL_WITHOUT_MATCH)
    private val ferdigstillWithoutSupportedRuntimeChannel = counter(FERDIGSTILL_WITHOUT_SUPPORTED_RUNTIME_CHANNEL)
    private val ferdigstillWithInvalidStoredCreate = counter(FERDIGSTILL_WITH_INVALID_STORED_CREATE)
    private val deliveryClaimed = counter(DELIVERY_CLAIMED)
    private val deliveryEmptyPolls = counter(DELIVERY_EMPTY_POLLS)

    override fun inboxClaimed(count: Int) = inboxClaimed.increment(count.toDouble())

    override fun inboxEmptyPoll() = inboxEmptyPolls.increment()

    override fun inboxProcessed() = inboxProcessed.increment()

    override fun inboxDropped(reason: DropReason) =
        Counter
            .builder(INBOX_MESSAGE_DROPPED)
            .tag(TAG_REASON, reason.name.lowercase())
            .register(registry)
            .increment()

    override fun inboxFailed() = inboxFailed.increment()

    override fun inboxOutsideSendingWindow(reason: String) = inboxOutsideSendingWindow.increment()

    override fun ferdigstillWithoutMatch() = ferdigstillWithoutMatch.increment()

    override fun ferdigstillWithoutSupportedRuntimeChannel() = ferdigstillWithoutSupportedRuntimeChannel.increment()

    override fun ferdigstillWithInvalidStoredCreate() = ferdigstillWithInvalidStoredCreate.increment()

    override fun deliveryClaimed(count: Int) = deliveryClaimed.increment(count.toDouble())

    override fun deliveryEmptyPoll() = deliveryEmptyPolls.increment()

    override fun deliverySent(channel: Channel) = delivery(channel, result = RESULT_SENT)

    override fun deliveryFailed(channel: Channel) = delivery(channel, result = RESULT_FAILED)

    override fun narmesteLederMissing(reason: NarmesteLederMissingReason) =
        Counter
            .builder(NARMESTE_LEDER_MISSING)
            .tag(TAG_REASON, reason.name.lowercase())
            .register(registry)
            .increment()

    private fun delivery(
        channel: Channel,
        result: String,
    ) = Counter
        .builder(DELIVERY)
        .tag(TAG_CHANNEL, channel.name.lowercase())
        .tag(TAG_RESULT, result)
        .register(registry)
        .increment()

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
        const val FERDIGSTILL_WITHOUT_MATCH = "ferdigstill.uten.treff"
        const val FERDIGSTILL_WITHOUT_SUPPORTED_RUNTIME_CHANNEL = "ferdigstill.uten.runtime.kanal"
        const val FERDIGSTILL_WITH_INVALID_STORED_CREATE = "ferdigstill.lagret.opprett.ugyldig"

        const val DELIVERY_CLAIMED = "delivery.claimed"
        const val DELIVERY_EMPTY_POLLS = "delivery.empty.polls"
        const val DELIVERY = "delivery"
        const val NARMESTE_LEDER_MISSING = "narmeste.leder.missing"

        const val TAG_REASON = "reason"
        const val TAG_CHANNEL = "channel"
        const val TAG_RESULT = "result"

        const val RESULT_SENT = "sent"
        const val RESULT_FAILED = "failed"
    }
}
