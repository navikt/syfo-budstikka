package no.nav.budstikka.application

import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.infrastructure.database.config.TransactionRunner
import java.util.UUID

/**
 * Effektueringen (B28, imperativt skall): skriver utfallet av [Decision] for ÉN inbox-melding til
 * databasen i ÉN transaksjon — delivery-rad(er) + inbox-status commits alt-eller-ingenting. Dette er
 * steget `DecisionProcess` bevisst lot stå åpent («skriving av leveranse-rad(er) +
 * inbox_hendelse.status i én DB-tx»).
 *
 * Per-melding atomisk er en hard invariant: én meldings feil ruller aldri tilbake en annens. Eksterne
 * oppslag (grunnlagsinnhenting) skjer FØR denne kalles, utenfor transaksjonen.
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
                    // CAS først: bare den workeren som vinner CLAIMED→PROCESSED skriver delivery-rader.
                    // En taper i et lease-kappløp treffer 0 rader og skriver ingenting (exactly-once).
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
