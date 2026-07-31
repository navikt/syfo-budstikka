package no.nav.budstikka.application.port

import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DropReason

/**
 * Metrics port (issue #28) for decision and delivery workers. The application layer emits domain
 * events through this port; a Micrometer adapter in infrastructure counts them in the shared
 * Prometheus registry. This keeps workers free from Micrometer imports (the same port/adapter seam
 * as [TransactionRunner] and repositories, ADR 0007).
 *
 * Contract: implementations only count; they never throw or perform I/O, so a metrics failure
 * cannot disrupt effectuation. Labels stay low-cardinality and PII-free ([Channel] names and fixed
 * results), never fnr, event ID, or other personal data. [inboxDropped] accepts [DropReason], not a
 * free string, specifically to enforce low cardinality in the port contract.
 */
interface DispatchMetrics {
    fun inboxClaimed(count: Int)

    fun inboxEmptyPoll()

    fun inboxProcessed()

    fun inboxDropped(reason: DropReason)

    fun inboxFailed()

    fun deliveryClaimed(count: Int)

    fun deliveryEmptyPoll()

    fun deliverySent(channel: Channel)

    fun deliveryFailed(channel: Channel)

    fun inboxOutsideSendingWindow(reason: String)
}

/** No-op metrics port for tests and runs without a registry. */
object NoDispatchMetrics : DispatchMetrics {
    override fun inboxClaimed(count: Int) = Unit

    override fun inboxEmptyPoll() = Unit

    override fun inboxProcessed() = Unit

    override fun inboxDropped(reason: DropReason) = Unit

    override fun inboxFailed() = Unit

    override fun deliveryClaimed(count: Int) = Unit

    override fun deliveryEmptyPoll() = Unit

    override fun deliverySent(channel: Channel) = Unit

    override fun deliveryFailed(channel: Channel) = Unit

    override fun inboxOutsideSendingWindow(reason: String) = Unit
}
