package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.domain.dispatch.DispatchHeader
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import java.util.Optional

internal fun testRecord(
    value: String?,
    topic: String = TOPIC,
    partition: Int = 0,
    offset: Long = 0L,
    key: String = "key",
    eventId: String? = null,
): ConsumerRecord<String, String?> =
    if (eventId != null) {
        val headers = RecordHeaders()
        headers.add(DispatchHeader.EVENT_ID, eventId.toByteArray(Charsets.UTF_8))
        ConsumerRecord(topic, partition, offset, offset, TimestampType.NO_TIMESTAMP_TYPE, -1, -1, key, value, headers, Optional.empty())
    } else {
        ConsumerRecord(topic, partition, offset, key, value)
    }
