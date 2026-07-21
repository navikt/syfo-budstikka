package no.nav.budstikka.application

import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.domain.decision.Decision
import java.util.UUID

/**
 * Effektueringen (B28, imperativt skall): skriver utfallet av [Decision] for ÉN inbox-melding til
 * databasen i ÉN transaksjon — delivery-rad(er) + inbox-status commits alt-eller-ingenting. Dette er
 * steget `DecisionProcess` bevisst lot stå åpent («skriving av leveranse-rad(er) +
 * inbox_hendelse.status i én DB-tx»).
 *
 * Hver inbox-melding behandles atomisk: Feil i én melding ruller bare tilbake den meldingen, ikke andre.
 * Eksterne oppslag (grunnlagsinnhenting) skjer før denne kalles, utenfor transaksjonen.
 *
 * [EffectuateDecision] utfører effectuate i en transaksjon.
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
                    // Bare worker som vinner CLAIMED->PROCESSED skriver delivery-rader.
                    if (inboxMessageRepository.markProcessedInTransaction(inboxEventId)) {
                        deliveryRepository.saveInTransaction(inboxEventId, decision.deliveries)
                    }
                }

                is Decision.Dropped ->
                    inboxMessageRepository.markDroppedInTransaction(inboxEventId, decision.reason.name)

                is Decision.Failed ->
                    inboxMessageRepository.markFailedInTransaction(inboxEventId, decision.errorMessage)
            }
        }
}
