package no.nav.budstikka.application

import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.domain.decision.Decision
import java.util.UUID

/**
 * Persists one [Decision] atomically: delivery rows and inbox state commit or roll back together.
 * External lookups must finish before this transaction begins.
 */
class EffectuateDecision(
    private val transactionRunner: TransactionRunner,
    private val inboxMessageRepository: InboxMessageRepository,
    private val deliveryRepository: DeliveryRepository,
) {
    suspend fun effectuate(
        inboxEventId: UUID,
        decision: Decision,
    ): Unit =
        transactionRunner.transaction {
            when (decision) {
                is Decision.Processed -> {
                    // Only the worker winning CLAIMED->PROCESSED writes delivery rows.
                    if (inboxMessageRepository.markProcessedInTransaction(inboxEventId)) {
                        deliveryRepository.saveInTransaction(inboxEventId, decision.deliveries)
                    }
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
