package no.nav.budstikka.application

import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.domain.decision.Decision
import java.util.UUID

/**
 * Effectuation writes the [Decision] result for ONE inbox message to the database in ONE transaction:
 * delivery row(s) and `inbox_message.state` commit all or nothing. This is the persistence boundary
 * that follows [DecisionProcess].
 *
 * Each inbox message is handled atomically: a failure rolls back only that message, not others.
 * External lookups (input fetching) run before this call, outside the transaction.
 *
 * [EffectuateDecision] effectuates in a transaction.
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
