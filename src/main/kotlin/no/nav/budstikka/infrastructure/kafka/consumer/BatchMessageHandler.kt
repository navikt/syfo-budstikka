package no.nav.budstikka.infrastructure.kafka.consumer

import org.apache.kafka.clients.consumer.ConsumerRecord

fun interface BatchMessageHandler<K, V> {
    suspend fun handleBatch(records: List<ConsumerRecord<K, V>>)
}
