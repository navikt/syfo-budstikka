package no.nav.budstikka.infrastructure.kafka.consumer

import org.apache.kafka.clients.consumer.ConsumerRecord

fun interface MessageHandler<K, V> {
    suspend fun handle(record: ConsumerRecord<K, V>)
}
