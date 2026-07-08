package no.nav.budstikka.infrastructure.database.inbox

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.util.UUID
import kotlin.time.Clock

data class DeadLetterRecord(
    val payload: String,
    val topic: String,
    val partisjon: Int,
    val kafkaOffset: Long,
    val kafkaKey: String?,
    val feilaarsak: String,
    val feilmelding: String?,
)

interface InboxRepository {
    suspend fun lagreHendelse(
        eventId: UUID,
        payload: String,
    ): Boolean

    suspend fun lagreFeilet(record: DeadLetterRecord)
}

class InboxRepositoryImpl(
    private val database: Database,
) : InboxRepository {
    override suspend fun lagreHendelse(
        eventId: UUID,
        payload: String,
    ): Boolean =
        database.transact {
            val now = Clock.System.now()
            InboxHendelseTable
                .insertIgnore {
                    it[InboxHendelseTable.eventId] = eventId
                    it[InboxHendelseTable.payload] = payload
                    it[InboxHendelseTable.mottattTid] = now
                }.insertedCount > 0
        }

    override suspend fun lagreFeilet(record: DeadLetterRecord) {
        database.transact {
            val now = Clock.System.now()
            InboxFeiletTable.insertIgnore {
                it[InboxFeiletTable.payload] = record.payload
                it[InboxFeiletTable.topic] = record.topic
                it[InboxFeiletTable.partisjon] = record.partisjon
                it[InboxFeiletTable.kafkaOffset] = record.kafkaOffset
                it[InboxFeiletTable.kafkaKey] = record.kafkaKey
                it[InboxFeiletTable.feilaarsak] = record.feilaarsak
                it[InboxFeiletTable.feilmelding] = record.feilmelding
                it[InboxFeiletTable.mottattTid] = now
            }
        }
    }
}
