package no.nav.budstikka.infrastructure.database.formidling

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

interface DeadLetterFormidlingRepository {
    suspend fun save(record: DeadLetterRecord)
}

class DeadLetterFormidlingRepositoryImpl(
    private val database: Database,
) : DeadLetterFormidlingRepository {
    override suspend fun save(record: DeadLetterRecord) {
        database.transact {
            val now = Clock.System.now()
            DeadLetterFormidlingTable.insertIgnore {
                it[DeadLetterFormidlingTable.payload] = record.payload
                it[DeadLetterFormidlingTable.topic] = record.topic
                it[DeadLetterFormidlingTable.partition] = record.partition
                it[DeadLetterFormidlingTable.kafkaOffset] = record.kafkaOffset
                it[DeadLetterFormidlingTable.kafkaKey] = record.kafkaKey
                it[DeadLetterFormidlingTable.failureReason] = record.failureReason
                it[DeadLetterFormidlingTable.errorMessage] = record.errorMessage
                it[DeadLetterFormidlingTable.receivedAt] = now
            }
        }
    }
}
