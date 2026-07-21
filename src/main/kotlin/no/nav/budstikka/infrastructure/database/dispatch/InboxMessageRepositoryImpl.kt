package no.nav.budstikka.infrastructure.database.dispatch

import net.logstash.logback.argument.StructuredArguments.kv
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
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class InboxMessageRepositoryImpl(
    private val database: Database,
) : InboxMessageRepository {
    private val logger = LoggerFactory.getLogger(InboxMessageRepositoryImpl::class.java)

    override suspend fun saveBatch(messages: List<InboxMessage>) {
        if (messages.isEmpty()) {
            return
        }
        database.transact {
            val now = Clock.System.now()
            InboxMessageTable.batchInsert(messages, ignore = true) { message ->
                this[InboxMessageTable.eventId] = message.eventId
                this[InboxMessageTable.content] = message.content
                this[InboxMessageTable.reference] = message.reference
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
                    .select(InboxMessageTable.eventId, InboxMessageTable.reference, InboxMessageTable.content)
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
                            reference = row[InboxMessageTable.reference],
                            content = row[InboxMessageTable.content],
                        )
                    }
            if (claimed.isNotEmpty()) {
                InboxMessageTable.update({ InboxMessageTable.eventId inList claimed.map { it.eventId } }) {
                    it[state] = InboxMessageState.CLAIMED.name
                    it[nextAttemptTime] = now + lease
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
        logger.warn(
            "Failed poison inbox message(s) after reaching max attempts {} {}",
            kv("poisonCount", poisonIds.size),
            kv("maxAttempts", maxAttempts),
        )
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
