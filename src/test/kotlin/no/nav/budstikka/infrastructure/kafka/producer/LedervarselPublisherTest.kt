package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.LedervarselInactivate
import no.nav.budstikka.domain.dispatch.Oppgavetype
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.MutableClock
import kotlin.time.Instant

class LedervarselPublisherTest :
    FunSpec({
        val reference = "00000000-0000-0000-0000-000000000601"
        val now = Instant.parse("2026-07-17T08:30:00Z")

        test("publishes LedervarselCreate as DineSykmeldteHendelse.opprettHendelse keyed by reference") {
            with(PublisherFixture()) {
                ledervarselPublisher(topic, recording, MutableClock(now)).publish(
                    reference = reference,
                    ledervarsel =
                        LedervarselCreate(
                            sykmeldt = TEST_SYKMELDT,
                            orgnummer = TEST_ORGNUMMER,
                            oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                            text = "Din ansatte er innkalt til dialogmøte",
                            link = "https://nav.no/dm/1",
                            visibleUntil = Instant.parse("2026-08-01T00:00:00Z"),
                        ),
                )

                with(recording.published.single()) {
                    this.topic shouldBe topic
                    // Kafka-key = reference (ikke fnr), = konsumentens id (dedup-PK `(id, oppgavetype)`).
                    id shouldBe reference

                    val json = value.parseJson()
                    json["id"]!!.jsonPrimitive.content shouldBe reference
                    json["ferdigstillHendelse"].shouldBeNull()

                    val opprett = json["opprettHendelse"]!!.jsonObject
                    opprett["ansattFnr"]!!.jsonPrimitive.content shouldBe TEST_SYKMELDT.value
                    opprett["orgnummer"]!!.jsonPrimitive.content shouldBe TEST_ORGNUMMER.value
                    // wireValue, ikke Kotlin-identifikatoren (B61/ADR 0008). Representativ verdi:
                    // enum-navnet og wireValue er identiske nå (DIALOGMOTE_INNKALLING), så denne
                    // asserten skiller ikke .name fra .wireValue før settet utvides med en divergerende verdi.
                    opprett["oppgavetype"]!!.jsonPrimitive.content shouldBe "DIALOGMOTE_INNKALLING"
                    opprett["tekst"]!!.jsonPrimitive.content shouldBe "Din ansatte er innkalt til dialogmøte"
                    opprett["lenke"]!!.jsonPrimitive.content shouldBe "https://nav.no/dm/1"
                    opprett["timestamp"]!!.jsonPrimitive.content shouldBe "2026-07-17T08:30:00Z"
                    opprett["utlopstidspunkt"]!!.jsonPrimitive.content shouldBe "2026-08-01T00:00:00Z"
                }
            }
        }

        test("omits optional fields when absent (consumer nullable contract)") {
            with(PublisherFixture()) {
                ledervarselPublisher(topic, recording, MutableClock(now)).publish(
                    reference = reference,
                    ledervarsel =
                        LedervarselCreate(
                            sykmeldt = TEST_SYKMELDT,
                            orgnummer = TEST_ORGNUMMER,
                            oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                            text = "Uten lenke",
                        ),
                )

                val opprett =
                    recording.published
                        .single()
                        .value
                        .parseJson()["opprettHendelse"]!!
                        .jsonObject
                opprett["lenke"].shouldBeNull()
                opprett["utlopstidspunkt"].shouldBeNull()
                opprett["timestamp"]!!.jsonPrimitive.content shouldBe "2026-07-17T08:30:00Z"
            }
        }

        test("publishes LedervarselInactivate as DineSykmeldteHendelse.ferdigstillHendelse keyed by reference") {
            with(PublisherFixture()) {
                ledervarselPublisher(topic, recording, MutableClock(now)).publish(
                    reference = reference,
                    ledervarsel =
                        LedervarselInactivate(
                            reference = reference,
                            sykmeldt = TEST_SYKMELDT,
                        ),
                )

                with(recording.published.single()) {
                    this.topic shouldBe topic
                    id shouldBe reference

                    val json = value.parseJson()
                    json["id"]!!.jsonPrimitive.content shouldBe reference
                    json["opprettHendelse"].shouldBeNull()
                    json["ferdigstillHendelse"]!!.jsonObject["timestamp"]!!.jsonPrimitive.content shouldBe
                        "2026-07-17T08:30:00Z"
                }
            }
        }
    })
