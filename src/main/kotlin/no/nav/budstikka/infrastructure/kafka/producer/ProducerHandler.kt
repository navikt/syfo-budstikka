package no.nav.budstikka.infrastructure.kafka.producer

fun interface ProducerHandler<in T> {
    fun handle(value: T): PublishedMessage
}

suspend fun <T> MessagePublisher.publish(
    value: T,
    handler: ProducerHandler<T>,
) {
    publish(handler.handle(value))
}
