package no.nav.budstikka.infrastructure.kafka.minside

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.formidling.MikrofrontendAktiver
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.formidlingJson
import no.nav.budstikka.infrastructure.kafka.producer.PublishedMessage

class MikrofrontendPublisherTest :
    FunSpec({
        test("binds the configured topic and publishes through the handler") {
            val recording = RecordingMessagePublisher()
            val publisher = mikrofrontendPublisher(topic = "configured-topic", messagePublisher = recording)

            publisher.publish(
                MikrofrontendAktiver(
                    personident = Personident("12345678901"),
                    mikrofrontendId = "sykmeldt-overview",
                ),
            )

            val expected =
                MicrofrontendMessage(
                    action = MinSideAction.ENABLED,
                    ident = "12345678901",
                    microfrontendId = "sykmeldt-overview",
                )
            recording.published.single() shouldBe
                PublishedMessage(
                    topic = "configured-topic",
                    id = "12345678901",
                    value = formidlingJson.encodeToString(expected),
                )
        }
    })
