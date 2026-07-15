package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.dispatch.MicrofrontendDisable
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.fakes.TEST_SYKMELDT
import kotlin.time.Instant

class MicrofrontendPublisherTest :
    FunSpec({
        test("publishes enable action to the configured topic keyed by personident") {
            with(PublisherFixture()) {
                microfrontendPublisher(topic, recording, platformConfig).publish(
                    MicrofrontendEnable(
                        personIdentifier = TEST_SYKMELDT,
                        mikrofrontendId = "sykmeldt-overview",
                        visibleUntil = Instant.parse("2026-07-10T00:00:00Z"),
                    ),
                )

                with(recording.published.single()) {
                    this.topic shouldBe topic
                    id shouldBe TEST_SYKMELDT.value
                    value.parseJson() shouldContainAll """
                        {
                          "@version": "3",
                          "@action": "enable",
                          "ident": "11111111111",
                          "microfrontend_id": "sykmeldt-overview",
                          "@initiated_by": "team-esyfo",
                          "sensitivitet": "high"
                        }
                    """.trimIndent().parseJson()
                }
            }
        }

        test("publishes disable action to the configured topic keyed by personident") {
            with(PublisherFixture()) {
                microfrontendPublisher(topic, recording, platformConfig).publish(
                    MicrofrontendDisable(
                        personIdentifier = TEST_SYKMELDT,
                        mikrofrontendId = "sykmeldt-overview",
                    ),
                )

                with(recording.published.single()) {
                    this.topic shouldBe topic
                    id shouldBe TEST_SYKMELDT.value
                    value.parseJson() shouldContainAll """
                        {
                          "@version": "3",
                          "@action": "disable",
                          "ident": "11111111111",
                          "microfrontend_id": "sykmeldt-overview",
                          "@initiated_by": "team-esyfo"
                        }
                    """.trimIndent().parseJson()
                }
            }
        }
    })
