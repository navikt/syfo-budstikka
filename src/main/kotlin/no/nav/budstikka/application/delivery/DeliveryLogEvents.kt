package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.esyfo.observability.Event

internal object DeliveryLogEvents {
    val handlerMissing =
        Event<Unit>(
            name = "delivery.handler.missing",
            level = ApplicationLogLevel.ERROR,
            message = "No handler for claimed channel; leaving row for lease reclaim",
            operation = "delivery.dispatch",
            errorCode = "DELIVERY_HANDLER_MISSING",
        )

    val claimSkipped =
        Event<Unit>(
            name = "delivery.claim.skipped",
            level = ApplicationLogLevel.WARN,
            message = "Skipping delivery because the row is no longer claimable or has spent its attempts",
            operation = "delivery.dispatch",
            errorCode = "DELIVERY_NOT_CLAIMABLE",
        )

    val sentTransitionFailed =
        Event<Unit>(
            name = "delivery.sent_transition.failed",
            level = ApplicationLogLevel.WARN,
            message = "Could not mark delivery as SENT because row is no longer CLAIMED",
            operation = "delivery.mark_sent",
            errorCode = "DELIVERY_STATE_CONFLICT",
        )

    val markedFailed =
        Event<DeliveryFailureContext>(
            name = "delivery.marked_failed",
            level = ApplicationLogLevel.WARN,
            message = "Marked delivery as FAILED",
            operation = "delivery.mark_failed",
            errorCode = "DELIVERY_FAILED",
            fields =
                mapOf(
                    MdcKeys.EVENT_ID to { it.eventId },
                    MdcKeys.DELIVERY_ID to { it.deliveryId },
                    MdcKeys.REFERENCE to { it.reference },
                    MdcKeys.REASON to { it.reason },
                ),
        )

    val failedTransitionFailed =
        Event<Unit>(
            name = "delivery.failed_transition.failed",
            level = ApplicationLogLevel.WARN,
            message = "Could not mark delivery as FAILED because row is no longer CLAIMED",
            operation = "delivery.mark_failed",
            errorCode = "DELIVERY_STATE_CONFLICT",
        )
}

internal data class DeliveryFailureContext(
    val eventId: String,
    val deliveryId: String,
    val reference: String,
    val reason: String,
)
