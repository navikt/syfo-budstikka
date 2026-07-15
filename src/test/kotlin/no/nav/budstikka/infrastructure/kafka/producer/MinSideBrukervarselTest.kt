package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.infrastructure.config.PlatformConfig

class MinSideBrukervarselTest :
    FunSpec({
        test("publishes BrukervarselCreate as TMS opprett action") {
            val messagePublisher = RecordingMessagePublisher()
            val publisher =
                minSideBrukervarselPublisher(
                    topic = "min-side.aapen-brukervarsel-v1",
                    messagePublisher = messagePublisher,
                    platformConfig = platformConfig,
                )
            val reference = "00000000-0000-0000-0000-000000000501"

            publisher.publish(
                reference = reference,
                brukervarsel =
                    BrukervarselCreate(
                        personIdentifier = PersonIdentifier("12345678901"),
                        varseltype = Varseltype.BESKJED,
                        text = "Hei",
                    ),
            )

            messagePublisher.published.shouldHaveSize(1)
            val message = messagePublisher.published.single()
            val value = Json.parseToJsonElement(message.value).jsonObject
            val text = value["tekster"]?.jsonArray?.single()?.jsonObject
            message.topic shouldBe "min-side.aapen-brukervarsel-v1"
            message.id shouldBe "12345678901"
            value.string("@event_name") shouldBe "opprett"
            value.string("varselId") shouldBe reference
            value.string("ident") shouldBe "12345678901"
            value.string("type") shouldBe "beskjed"
            value.string("sensitivitet") shouldBe "high"
            text?.string("tekst") shouldBe "Hei"
            text?.string("spraakkode") shouldBe "nb"
            value.jsonObject("produsent").string("cluster") shouldBe "dev-gcp"
            value.jsonObject("produsent").string("namespace") shouldBe "team-esyfo"
            value.jsonObject("produsent").string("appnavn") shouldBe "syfo-budstikka"
        }

        test("publishes BrukervarselInactivate as TMS inaktiver action") {
            val messagePublisher = RecordingMessagePublisher()
            val publisher =
                minSideBrukervarselPublisher(
                    topic = "min-side.aapen-brukervarsel-v1",
                    messagePublisher = messagePublisher,
                    platformConfig = platformConfig,
                )
            val reference = "00000000-0000-0000-0000-000000000501"

            publisher.publish(
                reference = reference,
                brukervarsel =
                    BrukervarselInactivate(
                        reference = reference,
                        sykmeldt = PersonIdentifier("12345678901"),
                    ),
            )

            messagePublisher.published.shouldHaveSize(1)
            val message = messagePublisher.published.single()
            val value = Json.parseToJsonElement(message.value).jsonObject
            message.topic shouldBe "min-side.aapen-brukervarsel-v1"
            message.id shouldBe "12345678901"
            value.string("@event_name") shouldBe "inaktiver"
            value.string("varselId") shouldBe reference
            value.jsonObject("produsent").string("cluster") shouldBe "dev-gcp"
            value.jsonObject("produsent").string("namespace") shouldBe "team-esyfo"
            value.jsonObject("produsent").string("appnavn") shouldBe "syfo-budstikka"
        }
    })

private val platformConfig =
    PlatformConfig(
        clusterName = "dev-gcp",
        namespace = "team-esyfo",
        appName = "syfo-budstikka",
    )

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.jsonObject(key: String): JsonObject = this.getValue(key).jsonObject
