package no.nav.budstikka.infrastructure.kafka.formidling

import kotlinx.serialization.json.Json
import no.nav.budstikka.infrastructure.kafka.config.MessageHandler
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles

class InboxHandler : MessageHandler<String, String?> {
    private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(record: ConsumerRecord<String, String?>) {
        val payload = record.value() ?: return
//            val dto = json.decodeFromString<SykmeldingHendelseDto>(payload)
        logger.info(
            "Processed sykmelding hendelse for topic={}, partition={}, offset={}",
            record.topic(),
            record.partition(),
            record.offset(),
        )
    }
}
