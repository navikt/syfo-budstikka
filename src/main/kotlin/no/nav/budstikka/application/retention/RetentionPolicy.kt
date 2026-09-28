package no.nav.budstikka.application.retention

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class RetentionPolicy(
    val inboxAndDeadLetterRetention: Duration = 100.days,
    val deliveryRetention: Duration = 180.days,
    val eligibleDeliveryStates: Set<String> = setOf("SENT", "FAILED"),
    val eligibleInboxStates: Set<String> = setOf("PROCESSED", "DROPPED", "FAILED"),
    val inboxAbsoluteRetention: Duration = 365.days,
) {
    init {
        require(inboxAbsoluteRetention >= inboxAndDeadLetterRetention) {
            "inboxAbsoluteRetention must be at least inboxAndDeadLetterRetention"
        }
    }
}
