package no.nav.budstikka.application.inbox

import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxClaim
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.domain.decision.Decision

/**
 * Persists one [Decision] atomically: delivery rows and inbox state commit or roll back together.
 * External lookups must finish before this transaction begins. Returns whether this worker won the
 * state transition under its claim and persisted the decision.
 */
class EffectuateDecision(
    private val transactionRunner: TransactionRunner,
    private val inboxMessageRepository: InboxMessageRepository,
    private val deliveryRepository: DeliveryRepository,
) {
    suspend fun effectuate(
        claim: InboxClaim,
        decision: Decision,
    ): Boolean =
        transactionRunner.transaction {
            when (decision) {
                is Decision.Processed -> {
                    // Only the worker winning CLAIMED->PROCESSED writes delivery rows.
                    val transitioned = inboxMessageRepository.markProcessedInTransaction(claim)
                    if (transitioned) {
                        deliveryRepository.saveInTransaction(claim.eventId, decision.deliveries)
                    }
                    transitioned
                }

                is Decision.Dropped ->
                    inboxMessageRepository.markDroppedInTransaction(claim, decision.reason.name)

                is Decision.Failed ->
                    inboxMessageRepository.markFailedInTransaction(claim, decision.errorMessage)

                is Decision.NotInSendingWindow ->
                    inboxMessageRepository.markOutsideSendingWindowInTransaction(
                        claim,
                        decision.reason,
                        decision.nextRetry,
                    )
            }
        }
}
