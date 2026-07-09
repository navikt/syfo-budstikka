package no.nav.budstikka.infrastructure.kafka.minside

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import no.nav.budstikka.domain.formidling.Mikrofrontend
import no.nav.budstikka.domain.formidling.MikrofrontendAktiver
import no.nav.budstikka.domain.formidling.MikrofrontendDeaktiver
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.formidlingJson
import no.nav.budstikka.infrastructure.kafka.producer.MessagePublisher
import no.nav.budstikka.infrastructure.kafka.producer.PublishedMessage
import no.nav.budstikka.infrastructure.kafka.producer.publish
import kotlin.time.Instant

class MikrofrontendHandlerTest :
    FunSpec({
        test("publishes aktivering to minside topic keyed by personident") {
            val publisher = RecordingMessagePublisher()
            val command =
                MikrofrontendAktiver(
                    personident = Personident("12345678901"),
                    mikrofrontendId = "sykmeldt-overview",
                    synligTom = Instant.parse("2026-07-10T00:00:00Z"),
                )

            publisher.publish(command, MikrofrontendHandler)

            publisher.published.single() shouldBe
                PublishedMessage(
                    topic = MikrofrontendHandler.TOPIC,
                    id = "12345678901",
                    value = formidlingJson.encodeToString<Mikrofrontend>(command),
                )
        }

        test("publishes deaktivering to minside topic keyed by personident") {
            val publisher = RecordingMessagePublisher()
            val command =
                MikrofrontendDeaktiver(
                    personident = Personident("12345678901"),
                    mikrofrontendId = "sykmeldt-overview",
                )

            publisher.publish(command, MikrofrontendHandler)

            publisher.published.single() shouldBe
                PublishedMessage(
                    topic = MikrofrontendHandler.TOPIC,
                    id = "12345678901",
                    value = formidlingJson.encodeToString<Mikrofrontend>(command),
                )
        }
    })

private class RecordingMessagePublisher : MessagePublisher {
    val published = mutableListOf<PublishedMessage>()

    override suspend fun publish(message: PublishedMessage) {
        published += message
    }
}
