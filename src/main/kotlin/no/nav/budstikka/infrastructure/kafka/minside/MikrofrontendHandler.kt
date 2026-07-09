package no.nav.budstikka.infrastructure.kafka.minside

import kotlinx.serialization.encodeToString
import no.nav.budstikka.domain.formidling.Formidlingsinnhold
import no.nav.budstikka.domain.formidling.MikrofrontendAktiver
import no.nav.budstikka.domain.formidling.MikrofrontendDeaktiver
import no.nav.budstikka.domain.formidling.formidlingJson
import no.nav.budstikka.infrastructure.kafka.producer.ProducerHandler
import no.nav.budstikka.infrastructure.kafka.producer.PublishedMessage

object MikrofrontendHandler : ProducerHandler<Formidlingsinnhold> {
    override fun handle(value: Formidlingsinnhold): PublishedMessage =
        PublishedMessage(
            topic = TOPIC,
            id = value.partisjonsnokkel,
            value =
                when (value) {
                    is MikrofrontendAktiver -> formidlingJson.encodeToString(value)
                    is MikrofrontendDeaktiver -> formidlingJson.encodeToString(value)
                    else -> error("Unsupported mikrofrontend payload: ${value::class.simpleName}")
                },
        )

    const val TOPIC = "min-side.aapen-microfrontend-v1"
}
