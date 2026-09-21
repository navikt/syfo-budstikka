package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import no.nav.budstikka.contract.DittSykefravaerCreate
import no.nav.budstikka.contract.DittSykefravaerInactivate
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.MutableClock
import kotlin.time.Instant

class DittSykefravaerPublisherTest :
    FunSpec({
        val reference = "00000000-0000-0000-0000-000000000701"
        val now = Instant.parse("2026-07-17T08:30:00Z")

        test("publishes create using the Flex DTO shape and reference as Kafka key") {
            with(PublisherFixture()) {
                dittSykefravaerPublisher(topic, recording, MutableClock(now)).publish(
                    reference,
                    DittSykefravaerCreate(
                        personIdentifier = TEST_SYKMELDT,
                        text = "Ny innkalling",
                        meldingType = "DIALOGMOTE_INNKALLING",
                        link = "https://nav.no/sykefravaer",
                        visibleUntil = Instant.parse("2026-08-01T00:00:00Z"),
                    ),
                )

                with(recording.published.single()) {
                    this.topic shouldBe topic
                    id shouldBe reference
                    value shouldBe
                        """{"opprettMelding":{"tekst":"Ny innkalling","lenke":"https://nav.no/sykefravaer","variant":"INFO","lukkbar":true,"meldingType":"DIALOGMOTE_INNKALLING","synligFremTil":"2026-08-01T00:00:00Z"},"lukkMelding":null,"fnr":"${TEST_SYKMELDT.value}"}"""
                }
            }
        }

        test("publishes inactivate with a deterministic close timestamp and reference key") {
            with(PublisherFixture()) {
                dittSykefravaerPublisher(topic, recording, MutableClock(now)).publish(
                    reference,
                    DittSykefravaerInactivate(reference, TEST_SYKMELDT),
                )

                with(recording.published.single()) {
                    id shouldBe reference
                    value shouldBe
                        """{"opprettMelding":null,"lukkMelding":{"timestamp":"2026-07-17T08:30:00Z"},"fnr":"${TEST_SYKMELDT.value}"}"""
                }
            }
        }

        test("publishes explicit nulls for nullable create fields") {
            with(PublisherFixture()) {
                dittSykefravaerPublisher(topic, recording, MutableClock(now)).publish(
                    reference,
                    DittSykefravaerCreate(
                        personIdentifier = TEST_SYKMELDT,
                        text = "Uten lenke",
                        meldingType = "VEILEDNING",
                    ),
                )

                recording.published.single().value shouldBe
                    """{"opprettMelding":{"tekst":"Uten lenke","lenke":null,"variant":"INFO","lukkbar":true,"meldingType":"VEILEDNING","synligFremTil":null},"lukkMelding":null,"fnr":"${TEST_SYKMELDT.value}"}"""
            }
        }

        test("rejects a legacy create without meldingType before publishing") {
            with(PublisherFixture()) {
                val failure =
                    shouldThrow<IllegalArgumentException> {
                        dittSykefravaerPublisher(topic, recording, MutableClock(now)).publish(
                            reference,
                            DittSykefravaerCreate(
                                personIdentifier = TEST_SYKMELDT,
                                text = "Legacy melding",
                            ),
                        )
                    }

                failure.message shouldBe "DittSykefravaerCreate requires meldingType before publication"
                recording.published.shouldBeEmpty()
            }
        }
    })
