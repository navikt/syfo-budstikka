package no.nav.budstikka.application.port

import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DropReason

/**
 * Counting-only metrics port: implementations must not throw or perform I/O. Labels are fixed,
 * low-cardinality, and PII-free.
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
