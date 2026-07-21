package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.DispatchContent
import java.util.UUID
import kotlin.time.Duration

/** En hydrert inbox-rad (ADR 0008): [eventId] fra Kafka-headeren, [reference]/[content] parset ved ingest. */
data class InboxMessage(
    val eventId: UUID,
    val reference: String,
    val content: DispatchContent,
)

interface InboxMessageRepository {
    suspend fun saveBatch(messages: List<InboxMessage>)

    /**
     * Griper (claimer) inntil [limit] mottatte meldinger for behandling og markerer dem CLAIMED med
     * en lease ([lease]) i ÉN transaksjon. Bruker `FOR UPDATE SKIP LOCKED`, slik at flere replicaer
     * får disjunkte bunker uten å blokkere hverandre (konkurrerende konsumenter, ingen leder — ADR
     * 0004). Plukker også opp CLAIMED-rader hvis leasen er utløpt (krasj-gjenoppretting). Radene er
     * usynlige for andre pollere til leasen løper ut eller de effektueres.
     *
     * Poison-gate (#71): en rad som er claimet [maxAttempts] ganger uten å nå terminal status blir
     * markert FAILED i stedet for å reclaimes på nytt, slik at en deterministisk feilrad ikke
     * blokkerer hodet av køen (`receivedAt ASC`) for alltid.
     */
    suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
    ): List<InboxMessage>

    /**
     * Terminal-overgangene for beslutnings-workeren. De åpner IKKE egen transaksjon — de kjøres
     * inne i [TransactionRunner.transaction], sammen
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
