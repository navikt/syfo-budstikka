package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Microfrontend as MicrofrontendContent

/**
 * The downstream object a decision changes when decisions for it must be persisted one at a time,
 * and a newer command cancels what an earlier one scheduled. Only microfrontends need this today.
 */
sealed interface OrderingKey {
    /** Min side identifies a microfrontend by this pair, independently of a dispatch reference. */
    data class Microfrontend(
        val personIdentifier: PersonIdentifier,
        val microfrontendId: String,
    ) : OrderingKey {
        /** Omits both parts: the person identifier and the surface shown to the person. */
        override fun toString(): String = "OrderingKey.Microfrontend()"
    }
}

/** The [OrderingKey] these deliveries change, or `null` when their order needs no protection. */
internal fun Decision.Processed.orderingKey(): OrderingKey? {
    val keys =
        deliveries
            .mapNotNull { draft ->
                (draft.content as? MicrofrontendContent)?.let {
                    OrderingKey.Microfrontend(it.personIdentifier, it.microfrontendId)
                }
            }.toSet()
    check(keys.size <= 1) { "A decision must change a single ordering key" }
    return keys.singleOrNull()
}
