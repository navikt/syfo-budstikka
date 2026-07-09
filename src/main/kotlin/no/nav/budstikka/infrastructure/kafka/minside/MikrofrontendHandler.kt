package no.nav.budstikka.infrastructure.kafka.minside

import kotlinx.serialization.encodeToString
import no.nav.budstikka.domain.formidling.Mikrofrontend
import no.nav.budstikka.domain.formidling.formidlingJson
import no.nav.budstikka.infrastructure.kafka.producer.ProducerHandler
import no.nav.budstikka.infrastructure.kafka.producer.PublishedMessage

object MikrofrontendHandler : ProducerHandler<Mikrofrontend> {
    override fun handle(value: Mikrofrontend): PublishedMessage =
        PublishedMessage(
            topic = TOPIC,
            id = value.partisjonsnokkel,
            value = formidlingJson.encodeToString(value),
        )

    const val TOPIC = "min-side.aapen-microfrontend-v1"
}
