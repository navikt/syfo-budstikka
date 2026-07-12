package no.nav.budstikka.application.port

import java.time.Duration
import java.util.UUID

data class InboxMessage(
    val eventId: UUID,
    val payload: String,
)

interface InboxMessageRepository {
    suspend fun saveBatch(events: List<Pair<UUID, String>>)

    /**
     * Griper (claimer) inntil [limit] mottatte meldinger for behandling og markerer dem CLAIMED med
     * en lease ([lease]) i ÉN transaksjon. Bruker `FOR UPDATE SKIP LOCKED`, slik at flere replicaer
     * får disjunkte bunker uten å blokkere hverandre (konkurrerende konsumenter, ingen leder — ADR
     * 0004). Plukker også opp CLAIMED-rader hvis leasen er utløpt (krasj-gjenoppretting). Radene er
     * usynlige for andre pollere til leasen løper ut eller de effektueres.
     */
    suspend fun claim(
        limit: Int,
        lease: Duration,
    ): List<InboxMessage>

    /**
     * Terminal-overgangene for beslutnings-workeren. De åpner IKKE egen transaksjon — de kjøres
     * inne i [no.nav.budstikka.infrastructure.database.config.TransactionRunner.transaction], sammen
     * med delivery-skrivingen, slik at én melding effektueres alt-eller-ingenting (#56). Overgangen
     * gjelder kun fra CLAIMED (idempotent compare-and-set: en allerede terminert eller re-claimet
     * melding gir `false`, og en taper i et lease-kappløp skriver da ingen delivery-rader).
     */
    fun markProcessedInTransaction(eventId: UUID): Boolean

    fun markDroppedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean

    fun markFailedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean
}
