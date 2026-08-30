package no.nav.budstikka.application.inbox

import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.domain.decision.Decision
import java.util.UUID

/**
 * Persists one [Decision] atomically: delivery rows and inbox state commit or roll back together.
 * External lookups must finish before this transaction begins. Returns whether this worker won the
 * state transition and persisted the decision.
 */
class EffectuateDecision(
    private val transactionRunner: TransactionRunner,
    private val inboxMessageRepository: InboxMessageRepository,
    private val deliveryRepository: DeliveryRepository,
) {
    suspend fun effectuate(
        inboxEventId: UUID,
        decision: Decision,
    ): Boolean =
        transactionRunner.transaction {
            when (decision) {
                is Decision.Processed -> {
                    // Only the worker winning CLAIMED->PROCESSED writes delivery rows.
                    val transitioned = inboxMessageRepository.markProcessedInTransaction(inboxEventId)
                    if (transitioned) {
                        deliveryRepository.saveInTransaction(inboxEventId, decision.deliveries)
                    }
                    transitioned
                }

                is Decision.Dropped ->
                    inboxMessageRepository.markDroppedInTransaction(inboxEventId, decision.reason.name)

                is Decision.Failed ->
                    inboxMessageRepository.markFailedInTransaction(inboxEventId, decision.errorMessage)

                is Decision.NotInSendingWindow ->
                    inboxMessageRepository.markOutsideSendingWindowInTransaction(
                        inboxEventId,
                        decision.reason,
                        decision.nextRetry,
                    )
            }
        }
}
