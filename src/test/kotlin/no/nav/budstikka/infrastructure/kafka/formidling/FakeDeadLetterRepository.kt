package no.nav.budstikka.infrastructure.kafka.formidling

import no.nav.budstikka.infrastructure.database.formidling.DeadLetterFormidlingRepository
import no.nav.budstikka.infrastructure.database.formidling.DeadLetterRecord

class FakeDeadLetterRepository : DeadLetterFormidlingRepository {
    val savedDeadLetters = mutableListOf<DeadLetterRecord>()

    override suspend fun save(record: DeadLetterRecord) {
        savedDeadLetters += record
    }
}
