package no.nav.budstikka.application.inbox

import no.nav.budstikka.domain.decision.DropReason

/**
 * Counting-only metrics port: implementations must not throw or perform I/O. Labels are fixed,
 * low-cardinality, and PII-free.
 */
interface InboxMetrics {
    fun claimed(count: Int)

    fun emptyPoll()

    fun processed()

    fun dropped(reason: DropReason)

    fun failed()

    fun outsideSendingWindow(reason: String)

    fun ferdigstillWithoutMatch()

    fun ferdigstillWithoutSupportedRuntimeChannel()

    fun ferdigstillWaitingForCreateSent()

    fun ferdigstillWithFailedCreate()

    fun ferdigstillWithInvalidStoredCreate()
}

object NoInboxMetrics : InboxMetrics {
    override fun claimed(count: Int) = Unit

    override fun emptyPoll() = Unit

    override fun processed() = Unit

    override fun dropped(reason: DropReason) = Unit

    override fun failed() = Unit

    override fun outsideSendingWindow(reason: String) = Unit

    override fun ferdigstillWithoutMatch() = Unit

    override fun ferdigstillWithoutSupportedRuntimeChannel() = Unit

    override fun ferdigstillWaitingForCreateSent() = Unit

    override fun ferdigstillWithFailedCreate() = Unit

    override fun ferdigstillWithInvalidStoredCreate() = Unit
}
