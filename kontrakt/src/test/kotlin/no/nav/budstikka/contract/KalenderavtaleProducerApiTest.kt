package no.nav.budstikka.contract

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID
import kotlin.time.Instant

private const val REFERENCE = "ref-1"
private val EVENT_ID = EventId(UUID.fromString("00000000-0000-4000-8000-000000000001"))

/**
 * Golden tests for the producer-facing kalenderavtale API: the exact bytes a Produsent puts on
 * the topic. Split out of [BudstikkaProducerApiTest]; the same rule applies. A change to any
 * expected string here is a wire change for every consumer and producer of `budstikka.v1`, and
 * must be treated as one.
 */
class KalenderavtaleProducerApiTest :
    FunSpec({
        context("kalenderavtale") {
            test("create keys on orgnummer and carries sakstype, leader, time, place and HTML email") {
                val encoded =
                    Budstikka.kalenderavtaleCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = "Dialogmøte med Test",
                        link = "https://nav.no/ag",
                        startAt = Instant.parse("2026-11-02T09:00:00Z"),
                        endAt = Instant.parse("2026-11-02T10:00:00Z"),
                        lokasjon = Kalenderavtale.Lokasjon("Gate 1", "0001", "Oslo"),
                        digital = true,
                        email = Kalenderavtale.EmailNotification.html("Tittel", "<p>E-post</p>"),
                    )

                encoded.key shouldBe SYNTHETIC_ORGNUMMER.value
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"KalenderavtaleCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"<p>E-post</p>","emailBodyFormat":"HTML"}},""" +
                    """"sakstype":"DIALOGMOTE","text":"Dialogmøte med Test","link":"https://nav.no/ag",""" +
                    """"startAt":"2026-11-02T09:00:00Z",""" +
                    """"endAt":"2026-11-02T10:00:00Z","lokasjon":{"adresse":"Gate 1","postnummer":"0001",""" +
                    """"poststed":"Oslo"},"digital":true,"tilstand":"VENTER_SVAR_FRA_ARBEIDSGIVER"}}"""
            }

            test("update carries only the requested changes and a plain-text email") {
                Budstikka
                    .kalenderavtaleUpdate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                        tilstand = Kalenderavtale.Tilstand.AVLYST,
                        email = Kalenderavtale.EmailNotification.plainText("Tittel", "E-post"),
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"KalenderavtaleUpdate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"E-post"}},"sakstype":"DIALOGMOTE",""" +
                    """"tilstand":"AVLYST","text":null}}"""
            }

            test("an update may carry a saksstatus, but not on its own") {
                Budstikka
                    .kalenderavtaleUpdate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                        tilstand = Kalenderavtale.Tilstand.AVLYST,
                        saksstatus = Arbeidsgiversak.Status.FERDIG,
                    ).value shouldContain """text":null,"saksstatus":"FERDIG"}}"""
                shouldThrow<IllegalArgumentException> {
                    Budstikka.kalenderavtaleUpdate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                        saksstatus = Arbeidsgiversak.Status.FERDIG,
                    )
                }.message shouldBe "tilstand, text or email must be set"
            }

            listOf(
                Kalenderavtale.Tilstand.VENTER_SVAR_FRA_ARBEIDSGIVER to "VENTER_SVAR_FRA_ARBEIDSGIVER",
                Kalenderavtale.Tilstand.ARBEIDSGIVER_VIL_AVLYSE to "ARBEIDSGIVER_VIL_AVLYSE",
                Kalenderavtale.Tilstand.ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED to "ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED",
                Kalenderavtale.Tilstand.ARBEIDSGIVER_HAR_GODTATT to "ARBEIDSGIVER_HAR_GODTATT",
                Kalenderavtale.Tilstand.AVLYST to "AVLYST",
                Kalenderavtale.Tilstand.AVHOLDT to "AVHOLDT",
            ).also { pairs -> pairs.map { it.first }.toSet() shouldBe Kalenderavtale.Tilstand.entries.toSet() }
                .forEach { (tilstand, wireValue) ->
                    test("tilstand $tilstand maps to $wireValue") {
                        Budstikka
                            .kalenderavtaleUpdate(
                                eventId = EVENT_ID,
                                reference = REFERENCE,
                                orgnummer = SYNTHETIC_ORGNUMMER,
                                sykmeldt = SYNTHETIC_SYKMELDT,
                                sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                                tilstand = tilstand,
                            ).value shouldContain """"tilstand":"$wireValue""""
                    }
                }

            test("text is limited to 300 characters on create and update") {
                fun create(text: String) =
                    Budstikka.kalenderavtaleCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = text,
                        link = "https://nav.no/ag",
                        startAt = Instant.parse("2026-11-02T09:00:00Z"),
                    )
                create("x".repeat(300)).value shouldContain "\"text\":"
                shouldThrow<IllegalArgumentException> { create("x".repeat(301)) }.message shouldBe
                    "text must be at most 300 characters"
                shouldThrow<IllegalArgumentException> {
                    Budstikka.kalenderavtaleUpdate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = "x".repeat(301),
                    )
                }.message shouldBe "text must be at most 300 characters"
            }
        }
    })
