package no.nav.budstikka.fakes

import no.nav.budstikka.application.inbox.InboxMetrics
import no.nav.budstikka.domain.decision.DropReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RecordingInboxMetrics : InboxMetrics {
    val claimedCount = AtomicInteger()
    val emptyPollCount = AtomicInteger()
    val processedCount = AtomicInteger()
    val failedCount = AtomicInteger()
    val droppedCounts = ConcurrentHashMap<DropReason, AtomicInteger>()
    val outsideSendingWindowCounts = ConcurrentHashMap<String, AtomicInteger>()
    val ferdigstillWithoutMatchCount = AtomicInteger()
    val ferdigstillWithoutSupportedRuntimeChannelCount = AtomicInteger()
    val ferdigstillWaitingForCreateSentCount = AtomicInteger()
    val ferdigstillWithFailedCreateCount = AtomicInteger()
    val ferdigstillWithInvalidStoredCreateCount = AtomicInteger()

    override fun claimed(count: Int) {
        claimedCount.addAndGet(count)
    }

    override fun emptyPoll() {
        emptyPollCount.incrementAndGet()
    }

    override fun processed() {
        processedCount.incrementAndGet()
    }

    override fun dropped(reason: DropReason) {
        droppedCounts.computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()
    }

    override fun failed() {
        failedCount.incrementAndGet()
    }

    override fun outsideSendingWindow(reason: String) {
        outsideSendingWindowCounts.computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()
    }

    override fun ferdigstillWithoutMatch() {
        ferdigstillWithoutMatchCount.incrementAndGet()
    }

    override fun ferdigstillWithoutSupportedRuntimeChannel() {
        ferdigstillWithoutSupportedRuntimeChannelCount.incrementAndGet()
    }

    override fun ferdigstillWaitingForCreateSent() {
        ferdigstillWaitingForCreateSentCount.incrementAndGet()
    }

    override fun ferdigstillWithFailedCreate() {
        ferdigstillWithFailedCreateCount.incrementAndGet()
    }

    override fun ferdigstillWithInvalidStoredCreate() {
        ferdigstillWithInvalidStoredCreateCount.incrementAndGet()
    }
}
