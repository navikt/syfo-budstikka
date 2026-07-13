package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toKotlinDuration

class InboxMessageRepositoryImpl(
    private val database: Database,
) : InboxMessageRepository {
    private val logger = LoggerFactory.getLogger(InboxMessageRepositoryImpl::class.java)

    override suspend fun saveBatch(events: List<Pair<UUID, String>>) {
        if (events.isEmpty()) {
            return
        }
        database.transact {
            val now = Clock.System.now()
            InboxMessageTable.batchInsert(events, ignore = true) { (eventId, payload) ->
                this[InboxMessageTable.eventId] = eventId
                this[InboxMessageTable.payload] = payload
                this[InboxMessageTable.receivedAt] = now
            }
        }
    }

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
    ): List<InboxMessage> {
        require(limit > 0) { "limit must be greater than 0" }
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        return database.transact {
            val now = Clock.System.now()
            failPoisonRows(now, maxAttempts)
            val claimed =
                InboxMessageTable
                    .select(InboxMessageTable.eventId, InboxMessageTable.payload)
                    .where {
                        (InboxMessageTable.state eq InboxMessageState.RECEIVED.name) or
                            (
                                (InboxMessageTable.state eq InboxMessageState.CLAIMED.name) and
                                    (InboxMessageTable.nextAttemptTime lessEq now) and
                                    (InboxMessageTable.attempt less maxAttempts)
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

    /**
     * Terminal-gate mot poison rows (#71): utløpte CLAIMED-rader som er claimet [maxAttempts] ganger
     * uten å nå terminal status markeres FAILED. Kjøres i samme transaksjon som claim, så en
     * deterministisk feilrad slutter å reclaimes og blokkerer ikke hodet av køen (`receivedAt ASC`).
     *
     * Poison-radene låses med `FOR UPDATE SKIP LOCKED` (som selve claim), slik at samtidige
     * replicaer terminerer disjunkte rader uten å blokkere hverandre (ADR 0004, ingen leder).
     */
    private fun failPoisonRows(
        now: Instant,
        maxAttempts: Int,
    ) {
        val poisonIds =
            InboxMessageTable
                .select(InboxMessageTable.eventId)
                .where {
                    (InboxMessageTable.state eq InboxMessageState.CLAIMED.name) and
                        (InboxMessageTable.nextAttemptTime lessEq now) and
                        (InboxMessageTable.attempt greaterEq maxAttempts)
                }.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .map { it[InboxMessageTable.eventId] }
        if (poisonIds.isEmpty()) {
            return
        }
        InboxMessageTable.update({ InboxMessageTable.eventId inList poisonIds }) {
            it[state] = InboxMessageState.FAILED.name
            it[nextAttemptTime] = null
            it[processedAt] = now
            it[errorMessage] = "Poison row failed after reaching $maxAttempts attempts"
        }
        logger.warn("Failed {} poison inbox message(s) after reaching {} attempts", poisonIds.size, maxAttempts)
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
