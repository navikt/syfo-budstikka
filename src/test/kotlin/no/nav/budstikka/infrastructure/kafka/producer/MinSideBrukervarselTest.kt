package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype

class MinSideBrukervarselTest :
    FunSpec({
        test("publishes BrukervarselCreate as TMS opprett action") {
            with(PublisherFixture()) {
                val reference = "00000000-0000-0000-0000-000000000501"
                minSideBrukervarselPublisher(
                    topic = topic,
                    messagePublisher = recording,
                    platformConfig = platformConfig,
                ).publish(
                    reference = reference,
                    brukervarsel =
                        BrukervarselCreate(
                            personIdentifier = PersonIdentifier("12345678901"),
                            varseltype = Varseltype.BESKJED,
                            text = "Hei",
                        ),
                )
                with(recording.published.single()) {
                    this.topic shouldBe topic
                    id shouldBe "12345678901"
                    value.parseJson() shouldContainAll
                        """
                        {
                          "type": "beskjed",
                          "varselId": "00000000-0000-0000-0000-000000000501",
                          "ident": "12345678901",
                          "sensitivitet": "high",
                          "tekster": [
                            {
                              "spraakkode": "nb",
                              "tekst": "Hei",
                              "default": true
                            }
                          ],
                          "produsent": {
                            "cluster": "dev-gcp",
                            "namespace": "team-esyfo",
                            "appnavn": "syfo-budstikka"
                          },
                          "@event_name": "opprett"
                        }
                        """.trimIndent().parseJson()
                }
            }
        }

        test("publishes BrukervarselInactivate as TMS inaktiver action") {
            with(PublisherFixture()) {
                val publisher =
                    minSideBrukervarselPublisher(
                        topic,
                        messagePublisher = recording,
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

                with(recording.published.single()) {
                    this.topic shouldBe topic
                    id shouldBe "12345678901"
                    value.parseJson() shouldContainAll
                        """
                        {
                          "varselId": "00000000-0000-0000-0000-000000000501",
                          "produsent": {
                            "cluster": "dev-gcp",
                            "namespace": "team-esyfo",
                            "appnavn": "syfo-budstikka"
                          },
                          "@event_name": "inaktiver"
                        }
                        """.trimIndent().parseJson()
                }
            }
        }
    })
