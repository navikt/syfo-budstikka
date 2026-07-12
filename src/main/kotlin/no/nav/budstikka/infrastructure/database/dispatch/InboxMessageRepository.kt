package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.toKotlinDuration

data class InboxMessage(
    val eventId: UUID,
    val payload: String,
)

interface InboxMessageRepository {
    suspend fun saveBatch(events: List<Pair<UUID, String>>): Int

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

class InboxMessageRepositoryImpl(
    private val database: Database,
) : InboxMessageRepository {
    override suspend fun saveBatch(events: List<Pair<UUID, String>>): Int {
        if (events.isEmpty()) {
            return 0
        }
        return database.transact {
            val placeholders = List(events.size) { "(?, ?)" }.joinToString(", ")
            val sql =
                """
                INSERT INTO inbox_message (event_id, payload)
                VALUES $placeholders
                ON CONFLICT (event_id) DO NOTHING
                """.trimIndent()
            val statement = TransactionManager.current().connection.prepareStatement(sql, false)
            try {
                var parameterIndex = 1
                events.forEach { (eventId, payload) ->
                    statement.set(parameterIndex++, eventId, InboxMessageTable.eventId.columnType)
                    statement.set(parameterIndex++, payload, InboxMessageTable.payload.columnType)
                }
                statement.executeUpdate()
            } finally {
                statement.closeIfPossible()
            }
        }
    }

    override suspend fun claim(
        limit: Int,
        lease: Duration,
    ): List<InboxMessage> {
        require(limit > 0) { "limit must be greater than 0" }
        return database.transact {
            val now = Clock.System.now()
            val claimed =
                InboxMessageTable
                    .selectAll()
                    .where {
                        (InboxMessageTable.state eq InboxMessageState.RECEIVED.name) or
                            (
                                (InboxMessageTable.state eq InboxMessageState.CLAIMED.name) and
                                    (InboxMessageTable.nextAttemptTime lessEq now)
                            )
                    }.orderBy(
                        InboxMessageTable.receivedAt to SortOrder.ASC,
                        InboxMessageTable.eventId to SortOrder.ASC,
                    ).limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .map { row ->
                        InboxMessage(
                            eventId = row[InboxMessageTable.eventId],
                            payload = row[InboxMessageTable.payload],
                        )
                    }
            if (claimed.isNotEmpty()) {
                val leaseDeadline = now + lease.toKotlinDuration()
                InboxMessageTable.update({ InboxMessageTable.eventId inList claimed.map { it.eventId } }) {
                    it[state] = InboxMessageState.CLAIMED.name
                    it[nextAttemptTime] = leaseDeadline
                    it[attempt] = attempt + 1
                }
            }
            claimed
        }
    }

    override fun markProcessedInTransaction(eventId: UUID): Boolean =
        terminate(eventId, state = InboxMessageState.PROCESSED, dropReason = null, errorMessage = null)

    override fun markDroppedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = terminate(eventId, state = InboxMessageState.DROPPED, dropReason = reason, errorMessage = null)

    override fun markFailedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = terminate(eventId, state = InboxMessageState.FAILED, dropReason = null, errorMessage = reason)

    private fun terminate(
        eventId: UUID,
        state: InboxMessageState,
        dropReason: String?,
        errorMessage: String?,
    ): Boolean =
        InboxMessageTable.update({
            (InboxMessageTable.eventId eq eventId) and (InboxMessageTable.state eq InboxMessageState.CLAIMED.name)
        }) {
            it[InboxMessageTable.state] = state.name
            it[InboxMessageTable.dropReason] = dropReason
            it[InboxMessageTable.errorMessage] = errorMessage
            it[processedAt] = Clock.System.now()
        } > 0
}
