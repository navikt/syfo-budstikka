package no.nav.budstikka.infrastructure.database.delivery

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.esyfo.observability.Event

internal object DeliveryRepositoryLogEvents {
    val poisonRowFailed =
        Event<PoisonDeliveryContext>(
            name = "delivery.poison_row.failed",
            level = ApplicationLogLevel.WARN,
            message = "Failed poison delivery row after reaching max attempts",
            operation = "delivery.fail_poison_row",
            errorCode = "DELIVERY_MAX_ATTEMPTS_REACHED",
            fields =
                mapOf(
                    MdcKeys.DELIVERY_ID to { it.deliveryId },
                    MdcKeys.EVENT_ID to { it.eventId },
                    MdcKeys.REFERENCE to { it.reference },
                    MdcKeys.DELIVERY_OPERATION to { it.deliveryOperation },
                    MdcKeys.DELIVERY_CHANNEL to { it.deliveryChannel },
                    MdcKeys.DELIVERY_COUNT to { it.deliveryCount },
                    MdcKeys.MAX_ATTEMPTS to { it.maxAttempts },
                ),
        )
}

internal data class PoisonDeliveryContext(
    val deliveryId: String,
    val eventId: String?,
    val reference: String,
    val deliveryOperation: String,
    val deliveryChannel: String,
    val deliveryCount: Int,
    val maxAttempts: Int,
)
