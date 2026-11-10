package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.inbox.ArbeidsgiverSnapshotResolver
import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DeliverySnapshot
import no.nav.budstikka.domain.decision.toDeliveryDraft

/**
 * Compatibility only for persisted LEGACY rows. Remove after old pending deliveries and writers
 * are gone. Normal resolved deliveries must never enter this register-lookup path.
 */
class LegacyDeliverySnapshotResolver(
    leaders: NarmesteLederLookup,
    names: PersonNameLookup,
    metrics: DeliveryMetrics,
) {
    private val snapshotResolver = ArbeidsgiverSnapshotResolver(leaders, names, metrics)
    private val logger = applicationLogger(LegacyDeliverySnapshotResolver::class.java)

    suspend fun resolve(delivery: ClaimedDelivery): Decision {
        check(delivery.snapshot == DeliverySnapshot.Legacy) { "Legacy resolver requires an explicit legacy snapshot" }
        logger.info("Resolving legacy delivery snapshot", mapOf("channel" to delivery.channel.name))
        return withChannelHandlerFailureContext(delivery.channel, "resolving legacy delivery snapshot") {
            snapshotResolver.resolve(Decision.Processed(listOf(delivery.payload.toDeliveryDraft(delivery.reference))))
        }
    }
}
