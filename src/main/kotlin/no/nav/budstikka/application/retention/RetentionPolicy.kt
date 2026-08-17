package no.nav.budstikka.application.retention

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class RetentionPolicy(
    val inboxAndDeadLetterRetention: Duration = 100.days,
    val deliveryRetention: Duration = 180.days,
    val eligibleDeliveryStates: Set<String> = setOf("SENT", "FAILED"),
)
