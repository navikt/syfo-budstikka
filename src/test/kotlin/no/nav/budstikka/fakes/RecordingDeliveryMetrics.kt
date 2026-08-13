package no.nav.budstikka.fakes

import no.nav.budstikka.application.delivery.DeliveryMetrics
import no.nav.budstikka.application.delivery.NarmesteLederMissingReason
import no.nav.budstikka.domain.decision.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RecordingDeliveryMetrics : DeliveryMetrics {
    val deliveryClaimed = AtomicInteger()
    val deliveryEmptyPolls = AtomicInteger()
    val deliverySent = ConcurrentHashMap<Channel, AtomicInteger>()
    val deliveryFailed = ConcurrentHashMap<Channel, AtomicInteger>()
    val narmesteLederMissing = ConcurrentHashMap<NarmesteLederMissingReason, AtomicInteger>()

    override fun claimed(count: Int) {
        deliveryClaimed.addAndGet(count)
    }

    override fun emptyPoll() {
        deliveryEmptyPolls.incrementAndGet()
    }

    override fun sent(channel: Channel) {
        deliverySent.computeIfAbsent(channel) { AtomicInteger() }.incrementAndGet()
    }

    override fun failed(channel: Channel) {
        deliveryFailed.computeIfAbsent(channel) { AtomicInteger() }.incrementAndGet()
    }

    override fun narmesteLederMissing(reason: NarmesteLederMissingReason) {
        narmesteLederMissing.computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()
    }
}
