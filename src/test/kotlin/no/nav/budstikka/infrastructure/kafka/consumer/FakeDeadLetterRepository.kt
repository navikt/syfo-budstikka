package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterRecord

class FakeDeadLetterRepository : DeadLetterMessageRepository {
    val savedDeadLetters = mutableListOf<DeadLetterRecord>()

    override suspend fun save(record: DeadLetterRecord) {
        savedDeadLetters += record
    }
}
