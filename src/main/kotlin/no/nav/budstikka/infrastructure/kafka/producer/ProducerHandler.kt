package no.nav.budstikka.infrastructure.kafka.producer

fun interface ProducerHandler<in T> {
    fun handle(value: T): OutboundMessage
}

suspend fun <T> MessagePublisher.publish(
    topic: String,
    value: T,
    handler: ProducerHandler<T>,
) {
    val outbound = handler.handle(value)
    publish(PublishedMessage(topic = topic, id = outbound.id, value = outbound.value))
}
