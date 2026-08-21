package no.nav.budstikka.application.inbox

import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.StoredCreateDelivery
import no.nav.budstikka.application.port.StoredCreateDeliveryState
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.contract.LedervarselCreate
import no.nav.budstikka.contract.LedervarselInactivate
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.domain.decision.isFerdigstill
import no.nav.budstikka.domain.decision.toFerdigstillMatch
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

internal val FERDIGSTILL_CREATE_SENT_RECHECK_DELAY = 30.seconds

sealed interface EffectuationResult {
    data object Completed : EffectuationResult

    data object Skipped : EffectuationResult

    /** A storage-derived FERDIGSTILL materialized this many INAKTIVER deliveries. */
    data class FerdigstillWithDelivery(
        val deliveryCount: Int,
    ) : EffectuationResult

    data object FerdigstillWithoutMatch : EffectuationResult

    data object FerdigstillWithoutSupportedRuntimeChannel : EffectuationResult

    /** A matching stored CREATE exists, but its delivery is not yet confirmed SENT. */
    data object FerdigstillWaitingForCreateSent : EffectuationResult

    /** The matching stored CREATE previously failed, so no outbound close can be derived. */
    data object FerdigstillWithFailedCreate : EffectuationResult

    /** The matching stored CREATE has incompatible persisted payload, channel, or recipient data. */
    data object FerdigstillWithInvalidStoredCreate : EffectuationResult
}

/**
 * Persists one [Decision] atomically: delivery rows and inbox state commit or roll back together.
 * External lookups must finish before this transaction begins.
 */
class EffectuateDecision(
    private val transactionRunner: TransactionRunner,
    private val inboxMessageRepository: InboxMessageRepository,
    private val deliveryRepository: DeliveryRepository,
    private val clock: Clock = Clock.System,
) {
    suspend fun effectuate(
        inboxMessage: InboxMessage,
        decision: Decision,
    ): EffectuationResult =
        transactionRunner.transaction {
            if (!inboxMessageRepository.lockClaimedForEffectuationInTransaction(inboxMessage.eventId)) {
                return@transaction EffectuationResult.Skipped
            }

            if (inboxMessage.content.isFerdigstill() && decision is Decision.Processed) {
                return@transaction effectuateFerdigstill(inboxMessage)
            }

            when (decision) {
                is Decision.Processed -> {
                    // Only the worker winning CLAIMED->PROCESSED writes delivery rows.
                    if (inboxMessageRepository.markProcessedInTransaction(inboxMessage.eventId)) {
                        deliveryRepository.saveInTransaction(inboxMessage.eventId, decision.deliveries)
                        EffectuationResult.Completed
                    } else {
                        EffectuationResult.Skipped
                    }
                }

                is Decision.Dropped -> {
                    inboxMessageRepository.markDroppedInTransaction(inboxMessage.eventId, decision.reason.name)
                    EffectuationResult.Completed
                }

                is Decision.Failed -> {
                    inboxMessageRepository.markFailedInTransaction(inboxMessage.eventId, decision.errorMessage)
                    EffectuationResult.Completed
                }

                is Decision.NotInSendingWindow -> {
                    inboxMessageRepository.markOutsideSendingWindowInTransaction(
                        inboxMessage.eventId,
                        decision.reason,
                        decision.nextRetry,
                    )
                    EffectuationResult.Completed
                }
            }
        }

    /**
     * The first lookup observes an already materialized CREATE, but never skips locking matching
     * WAIT/awakened-WAIT CREATE rows: duplicates must not materialize after FERDIGSTILL. After
     * those locks, the CREATE lookup is repeated because a creator may have won while this
     * transaction waited. Every locked held CREATE is cancelled once the FERDIGSTILL row reaches
     * its next durable state, whether that is PROCESSED or a technical WAIT for CREATE=SENT.
     */
    private fun effectuateFerdigstill(inboxMessage: InboxMessage): EffectuationResult {
        val match =
            inboxMessage.content.toFerdigstillMatch(inboxMessage.reference)
                ?: return markFerdigstillWithoutSupportedRuntimeChannel(inboxMessage.eventId)

        val createBeforeWaitingLocks = deliveryRepository.findCreateForFerdigstillInTransaction(match)
        val waitingCreateEventIds = inboxMessageRepository.lockWaitingCreatesForFerdigstillInTransaction(match)
        val create = deliveryRepository.findCreateForFerdigstillInTransaction(match) ?: createBeforeWaitingLocks

        return when (create?.state) {
            StoredCreateDeliveryState.SENT -> {
                val inactivateDraft = create.toInactivateDraft()
                when {
                    inactivateDraft != null -> {
                        if (!inboxMessageRepository.markProcessedInTransaction(inboxMessage.eventId)) {
                            EffectuationResult.Skipped
                        } else {
                            cancelLockedWaitingCreates(waitingCreateEventIds)
                            val deliveries = listOf(inactivateDraft)
                            deliveryRepository.saveInTransaction(inboxMessage.eventId, deliveries)
                            EffectuationResult.FerdigstillWithDelivery(deliveryCount = deliveries.size)
                        }
                    }

                    !inboxMessageRepository.markProcessedInTransaction(inboxMessage.eventId) -> {
                        EffectuationResult.Skipped
                    }

                    else -> {
                        cancelLockedWaitingCreates(waitingCreateEventIds)
                        EffectuationResult.FerdigstillWithInvalidStoredCreate
                    }
                }
            }

            StoredCreateDeliveryState.READY,
            StoredCreateDeliveryState.CLAIMED,
            -> {
                if (
                    !inboxMessageRepository.markWaitingForCreateSentInTransaction(
                        inboxMessage.eventId,
                        clock.now() + FERDIGSTILL_CREATE_SENT_RECHECK_DELAY,
                    )
                ) {
                    EffectuationResult.Skipped
                } else {
                    cancelLockedWaitingCreates(waitingCreateEventIds)
                    EffectuationResult.FerdigstillWaitingForCreateSent
                }
            }

            StoredCreateDeliveryState.FAILED -> {
                if (!inboxMessageRepository.markProcessedInTransaction(inboxMessage.eventId)) {
                    EffectuationResult.Skipped
                } else {
                    cancelLockedWaitingCreates(waitingCreateEventIds)
                    EffectuationResult.FerdigstillWithFailedCreate
                }
            }

            null ->
                when {
                    waitingCreateEventIds.isNotEmpty() -> {
                        if (!inboxMessageRepository.markProcessedInTransaction(inboxMessage.eventId)) {
                            EffectuationResult.Skipped
                        } else {
                            cancelLockedWaitingCreates(waitingCreateEventIds)
                            EffectuationResult.Completed
                        }
                    }

                    else -> {
                        check(inboxMessageRepository.markProcessedInTransaction(inboxMessage.eventId)) {
                            "Locked FERDIGSTILL row must remain processable"
                        }
                        EffectuationResult.FerdigstillWithoutMatch
                    }
                }
        }
    }

    private fun cancelLockedWaitingCreates(eventIds: List<UUID>) {
        eventIds.forEach { eventId ->
            check(inboxMessageRepository.markWaitingCreateProcessedInTransaction(eventId)) {
                "Locked waiting CREATE must remain cancellable"
            }
        }
    }

    private fun markFerdigstillWithoutSupportedRuntimeChannel(eventId: UUID): EffectuationResult {
        check(inboxMessageRepository.markProcessedInTransaction(eventId)) {
            "Locked FERDIGSTILL row must remain processable"
        }
        return EffectuationResult.FerdigstillWithoutSupportedRuntimeChannel
    }
}

private fun StoredCreateDelivery.toInactivateDraft(): DeliveryDraft? =
    when (val create = payload) {
        is BrukervarselCreate -> {
            val person = recipient as? Recipient.Person
            if (channel != Channel.BRUKERVARSEL || person == null || person.ident != create.personIdentifier) {
                null
            } else {
                DeliveryDraft(
                    reference = reference,
                    operation = Operation.INACTIVATE,
                    channel = channel,
                    recipient = recipient,
                    content = BrukervarselInactivate(reference, person.ident),
                )
            }
        }

        is LedervarselCreate -> {
            val person = recipient as? Recipient.Person
            if (channel != Channel.LEDERVARSEL || person == null || person.ident != create.sykmeldt) {
                null
            } else {
                DeliveryDraft(
                    reference = reference,
                    operation = Operation.INACTIVATE,
                    channel = channel,
                    recipient = recipient,
                    content = LedervarselInactivate(reference, person.ident),
                )
            }
        }

        is ArbeidsgivervarselCreate -> {
            val virksomhet = recipient as? Recipient.Virksomhet
            if (
                channel != Channel.ARBEIDSGIVERVARSEL ||
                virksomhet == null ||
                virksomhet.orgnummer != create.orgnummer ||
                createExternalId == null
            ) {
                null
            } else {
                DeliveryDraft(
                    reference = reference,
                    operation = Operation.INACTIVATE,
                    channel = channel,
                    recipient = recipient,
                    content = create,
                    createExternalId = createExternalId,
                )
            }
        }

        else -> null
    }
