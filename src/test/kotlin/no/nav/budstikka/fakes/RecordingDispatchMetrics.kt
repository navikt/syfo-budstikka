package no.nav.budstikka.fakes

import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DropReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RecordingDispatchMetrics : DispatchMetrics {
    val inboxClaimed = AtomicInteger()
    val inboxEmptyPolls = AtomicInteger()
    val inboxProcessed = AtomicInteger()
    val inboxFailed = AtomicInteger()
    val inboxDropped = ConcurrentHashMap<DropReason, AtomicInteger>()
    val deliveryClaimed = AtomicInteger()
    val deliveryEmptyPolls = AtomicInteger()
    val deliverySent = ConcurrentHashMap<Channel, AtomicInteger>()
    val deliveryFailed = ConcurrentHashMap<Channel, AtomicInteger>()

    override fun inboxClaimed(count: Int) {
        inboxClaimed.addAndGet(count)
    }

    override fun inboxEmptyPoll() {
        inboxEmptyPolls.incrementAndGet()
    }

    override fun inboxProcessed() {
        inboxProcessed.incrementAndGet()
    }

    override fun inboxDropped(reason: DropReason) {
        inboxDropped.computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()
    }

    override fun inboxFailed() {
        inboxFailed.incrementAndGet()
    }

    override fun deliveryClaimed(count: Int) {
        deliveryClaimed.addAndGet(count)
    }

    override fun deliveryEmptyPoll() {
        deliveryEmptyPolls.incrementAndGet()
    }

    override fun deliverySent(channel: Channel) {
        deliverySent.computeIfAbsent(channel) { AtomicInteger() }.incrementAndGet()
    }

    override fun deliveryFailed(channel: Channel) {
        deliveryFailed.computeIfAbsent(channel) { AtomicInteger() }.incrementAndGet()
    }
}
