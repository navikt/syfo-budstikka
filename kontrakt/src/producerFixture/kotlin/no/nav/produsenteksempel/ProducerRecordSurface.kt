package no.nav.produsenteksempel

import no.nav.budstikka.contract.EncodedDispatch

/** Compiles every value a producer needs when translating an encoded dispatch to a Kafka record. */
fun EncodedDispatch.toProducerRecordParts(): ProducerRecordParts =
    ProducerRecordParts(
        topic = topic,
        key = key,
        value = value,
        headers = headerBytes(),
    )

data class ProducerRecordParts(
    val topic: String,
    val key: String,
    val value: String,
    val headers: Map<String, ByteArray>,
)
