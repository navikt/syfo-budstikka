package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import no.nav.budstikka.domain.formidling.MikrofrontendAktiver
import no.nav.budstikka.domain.formidling.MikrofrontendDeaktiver
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.formidlingJson
import kotlin.time.Instant

private const val TOPIC = "min-side.aapen-microfrontend-v1"

class MicrofrontendPublisherTest :
    FunSpec({
        test("publishes aktivering to the configured topic keyed by personident") {
            val recording = RecordingMessagePublisher()

            microfrontendPublisher(TOPIC, recording).publish(
                MikrofrontendAktiver(
                    personident = Personident("12345678901"),
                    mikrofrontendId = "sykmeldt-overview",
                    synligTom = Instant.parse("2026-07-10T00:00:00Z"),
                ),
            )

            val expected =
                MicrofrontendMessage(
                    action = MinSideAction.ENABLE,
                    ident = "12345678901",
                    microfrontendId = "sykmeldt-overview",
                )
            recording.published.single() shouldBe
                PublishedMessage(
                    topic = TOPIC,
                    id = "12345678901",
                    value = formidlingJson.encodeToString(expected),
                )
        }

        test("publishes deaktivering to the configured topic keyed by personident") {
            val recording = RecordingMessagePublisher()

            microfrontendPublisher(TOPIC, recording).publish(
                MikrofrontendDeaktiver(
                    personident = Personident("12345678901"),
                    mikrofrontendId = "sykmeldt-overview",
                ),
            )

            val expected =
                MicrofrontendMessage(
                    action = MinSideAction.DISABLE,
                    ident = "12345678901",
                    microfrontendId = "sykmeldt-overview",
                )
            recording.published.single() shouldBe
                PublishedMessage(
                    topic = TOPIC,
                    id = "12345678901",
                    value = formidlingJson.encodeToString(expected),
                )
        }

        test("serializes the Min side wire contract") {
            val recording = RecordingMessagePublisher()

            microfrontendPublisher(TOPIC, recording).publish(
                MikrofrontendAktiver(
                    personident = Personident("12345678901"),
                    mikrofrontendId = "sykmeldt-overview",
                ),
            )

            val json = recording.published.single().value
            json shouldContain "\"@action\":\"enable\""
            json shouldContain "\"ident\":\"12345678901\""
            json shouldContain "\"microfrontend_id\":\"sykmeldt-overview\""
            json shouldContain "\"sensitivitet\":\"high\""
            json shouldContain "\"@initiated_by\":\"team-esyfo\""
        }
    })
