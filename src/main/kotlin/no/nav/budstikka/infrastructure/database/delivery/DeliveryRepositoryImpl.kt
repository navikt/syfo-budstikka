package no.nav.budstikka.infrastructure.database.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Recipient
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

class DeliveryRepositoryImpl(
    private val database: Database,
) : DeliveryRepository {
    private val logger = LoggerFactory.getLogger(DeliveryRepositoryImpl::class.java)

    override fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    ) {
        if (draft.isEmpty()) {
            return
        }
        DeliveryTable.batchInsert(draft) { draftEntry ->
            this[DeliveryTable.inboxEventId] = inboxEventId
            this[DeliveryTable.reference] = draftEntry.reference
            this[DeliveryTable.operation] = draftEntry.operation.name
            this[DeliveryTable.channel] = draftEntry.channel.name
            val (type, id) = draftEntry.recipient.toColumns()
            this[DeliveryTable.recipientType] = type
            this[DeliveryTable.recipientId] = id
            this[DeliveryTable.payload] = draftEntry.content
            this[DeliveryTable.createdAt] = Clock.System.now()
        }
    }

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
        channels: Set<Channel>,
    ): List<ClaimedDelivery> {
        require(limit > 0) { "limit must be greater than 0" }
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        require(channels.isNotEmpty()) { "channels must not be empty" }
        return database.transact {
            val now = Clock.System.now()
            val channelNames = channels.map(Channel::name)
            failPoisonRows(now, maxAttempts, channelNames)
            val claimed =
                DeliveryTable
                    .select(
                        DeliveryTable.id,
                        DeliveryTable.inboxEventId,
                        DeliveryTable.channel,
                        DeliveryTable.payload,
                    ).where {
                        (
                            (DeliveryTable.state eq DeliveryState.READY.name) or
                                (
                                    (DeliveryTable.state eq DeliveryState.CLAIMED.name) and
                                        (DeliveryTable.nextAttemptTime lessEq now) and
                                        (DeliveryTable.attempt less maxAttempts)
                                )
                        ) and
                            (DeliveryTable.channel inList channelNames)
                    }.orderBy(
                        DeliveryTable.createdAt to SortOrder.ASC,
                        DeliveryTable.id to SortOrder.ASC,
                    ).limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .map { row ->
                        ClaimedDelivery(
                            id = row[DeliveryTable.id],
                            inboxEventId = row[DeliveryTable.inboxEventId],
                            channel = Channel.valueOf(row[DeliveryTable.channel]),
                            payload = row[DeliveryTable.payload],
                        )
                    }
            if (claimed.isNotEmpty()) {
                val leaseDeadline = now + lease.toKotlinDuration()
                DeliveryTable.update({ DeliveryTable.id inList claimed.map { it.id } }) {
                    it[state] = DeliveryState.CLAIMED.name
                    it[nextAttemptTime] = leaseDeadline
                    it[attempt] = attempt + 1
                }
            }
            claimed
        }
    }

    /**
     * Terminal-gate mot poison rows (#71): utløpte CLAIMED-rader som er claimet [maxAttempts] ganger
     * uten å nå terminal status markeres FAILED. Kjøres i samme transaksjon som claim (begrenset til
     * [channelNames] workeren håndterer), så en deterministisk feilrad slutter å reclaimes og
     * blokkerer ikke hodet av køen (`createdAt ASC`).
     *
     * Poison-radene låses med `FOR UPDATE SKIP LOCKED` (som selve claim), slik at samtidige
     * replicaer terminerer disjunkte rader uten å blokkere hverandre (ADR 0004, ingen leder).
     */
    private fun failPoisonRows(
        now: Instant,
        maxAttempts: Int,
        channelNames: List<String>,
    ) {
        val poisonIds =
            DeliveryTable
                .select(DeliveryTable.id)
                .where {
                    (DeliveryTable.state eq DeliveryState.CLAIMED.name) and
                        (DeliveryTable.nextAttemptTime lessEq now) and
                        (DeliveryTable.attempt greaterEq maxAttempts) and
                        (DeliveryTable.channel inList channelNames)
                }.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .map { it[DeliveryTable.id] }
        if (poisonIds.isEmpty()) {
            return
        }
        DeliveryTable.update({ DeliveryTable.id inList poisonIds }) {
            it[state] = DeliveryState.FAILED.name
            it[nextAttemptTime] = null
            it[errorMessage] = "Poison row failed after reaching $maxAttempts attempts"
        }
        logger.warn("Failed {} poison delivery row(s) after reaching {} attempts", poisonIds.size, maxAttempts)
    }

    override suspend fun markSent(deliveryId: UUID): Boolean =
        markClaimedAsTerminal(deliveryId, state = DeliveryState.SENT, errorMessage = null)

    override suspend fun markFailed(
        deliveryId: UUID,
        reason: String,
    ): Boolean {
        require(reason.isNotBlank()) { "reason must not be blank" }
        return markClaimedAsTerminal(deliveryId, state = DeliveryState.FAILED, errorMessage = reason)
    }

    private suspend fun markClaimedAsTerminal(
        deliveryId: UUID,
        state: DeliveryState,
        errorMessage: String?,
    ): Boolean =
        database.transact {
            DeliveryTable.update({
                (DeliveryTable.id eq deliveryId) and (DeliveryTable.state eq DeliveryState.CLAIMED.name)
            }) {
                it[DeliveryTable.state] = state.name
                it[DeliveryTable.nextAttemptTime] = null
                it[DeliveryTable.errorMessage] = errorMessage
            } > 0
        }
}

private fun Recipient.toColumns(): Pair<String, String> =
    when (this) {
        is Recipient.Person -> "PERSON" to ident.value
        is Recipient.Virksomhet -> "VIRKSOMHET" to orgnummer.value
    }
