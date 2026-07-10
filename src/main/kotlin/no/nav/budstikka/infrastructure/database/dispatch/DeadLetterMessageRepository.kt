package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import kotlin.time.Clock

data class DeadLetterRecord(
    val payload: String,
    val topic: String,
    val partition: Int,
    val kafkaOffset: Long,
    val kafkaKey: String?,
    val failureReason: String,
    val errorMessage: String?,
)

interface DeadLetterMessageRepository {
    suspend fun save(record: DeadLetterRecord)
}

class DeadLetterMessageRepositoryImpl(
    private val database: Database,
) : DeadLetterMessageRepository {
    override suspend fun save(record: DeadLetterRecord) {
        database.transact {
            val now = Clock.System.now()
            DeadLetterMessageTable.insertIgnore {
                it[DeadLetterMessageTable.payload] = record.payload
                it[DeadLetterMessageTable.topic] = record.topic
                it[DeadLetterMessageTable.partition] = record.partition
                it[DeadLetterMessageTable.kafkaOffset] = record.kafkaOffset
                it[DeadLetterMessageTable.kafkaKey] = record.kafkaKey
                it[DeadLetterMessageTable.failureReason] = record.failureReason
                it[DeadLetterMessageTable.errorMessage] = record.errorMessage
                it[DeadLetterMessageTable.receivedAt] = now
            }
        }
    }
}
