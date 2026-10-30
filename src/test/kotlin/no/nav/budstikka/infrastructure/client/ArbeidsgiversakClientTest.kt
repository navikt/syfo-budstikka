package no.nav.budstikka.infrastructure.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.delivery.ArbeidsgiversakCreateRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakExtendRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakStatusRequest
import no.nav.budstikka.application.delivery.KalenderavtaleCreateRequest
import no.nav.budstikka.application.delivery.KalenderavtaleUpdateRequest
import no.nav.budstikka.application.delivery.KalenderavtaleUpdateResponse
import no.nav.budstikka.application.delivery.NarmesteLederExternalVarsling
import no.nav.budstikka.contract.Kalenderavtalelokasjon
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.ArbeidsgiverNotifikasjonConfig
import kotlin.time.Instant

private val LEADER_FNR = "1".repeat(11)
private const val REJECTED = "Arbeidsgiver notification API rejected request: "
private const val NOT_FOUND = "Arbeidsgiver notification API has not found the target yet: "

class ArbeidsgiversakClientTest :
    FunSpec({
        context("nySak") {
            test("sends case identity, leader recipient, link, Oslo hard delete and correlation id") {
                var body = ""
                var correlationId = ""
                val client =
                    client { request ->
                        body = (request.body as TextContent).text
                        correlationId = request.headers["X-Request-ID"].orEmpty()
                        respond("""{"data":{"nySak":{"__typename":"NySakVellykket","id":"1"}}}""", HttpStatusCode.OK)
                    }

                client.createCase(caseRequest()) shouldBe ArbeidsgiverNotificationResponse.Published

                body shouldContain "nySak"
                body shouldContain """"grupperingsid":"mote-1""""
                body shouldContain """"merkelapp":"Dialogmøte""""
                body shouldContain """"virksomhetsnummer":"123456789""""
                body shouldContain """"naermesteLederFnr":"$LEADER_FNR","ansattFnr":"00000000000""""
                body shouldContain """"tittel":"Dialogmøte med Test""""
                body shouldContain "initiellStatus: MOTTATT"
                body shouldContain """"hardDelete":{"den":"2026-12-01T12:00:00"}"""
                body shouldContain """"lenke":"https://nav.no/syk/dinesykmeldte/relasjon-1""""
                correlationId shouldBe "event-1"
            }

            test("maps every business result") {
                mapOf(
                    "DuplikatGrupperingsidEtterDelete" to
                        ArbeidsgiverNotificationResponse.CaseDeleted(REJECTED + "DuplikatGrupperingsidEtterDelete"),
                    "UgyldigMerkelapp" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMerkelapp"),
                    "UgyldigMottaker" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMottaker"),
                    "UkjentProdusent" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentProdusent"),
                    "UkjentRolle" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentRolle"),
                ).forEach { (typeName, expected) ->
                    client {
                        respond("""{"data":{"nySak":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                    }.createCase(caseRequest()) shouldBe expected
                }
            }
        }

        context("nySak duplicate") {
            fun duplicateThen(hentSak: String) =
                client { request ->
                    val body = (request.body as TextContent).text
                    if (body.contains("hentSak")) {
                        body shouldContain """"id":"sak-123""""
                        respond("""{"data":{"hentSak":$hentSak}}""", HttpStatusCode.OK)
                    } else {
                        respond(
                            """{"data":{"nySak":{"__typename":"DuplikatGrupperingsid","idTilEksisterende":"sak-123"}}}""",
                            HttpStatusCode.OK,
                        )
                    }
                }

            test("counts as created when the existing case belongs to the same organisation") {
                duplicateThen(
                    """{"__typename":"HentetSak","sak":{"virksomhetsnummer":"123456789","grupperingsid":"mote-1"}}""",
                ).createCase(caseRequest()) shouldBe ArbeidsgiverNotificationResponse.Published
            }

            test("is rejected when the case id is used by another organisation") {
                duplicateThen(
                    """{"__typename":"HentetSak","sak":{"virksomhetsnummer":"987654321","grupperingsid":"mote-1"}}""",
                ).createCase(caseRequest()) shouldBe
                    ArbeidsgiverNotificationResponse.Rejected(REJECTED + "DuplikatGrupperingsid in another organisation")
            }

            test("is rejected when the existing case cannot be found") {
                duplicateThen("""{"__typename":"SakFinnesIkke"}""").createCase(caseRequest()) shouldBe
                    ArbeidsgiverNotificationResponse.Rejected(REJECTED + "DuplikatGrupperingsid that could not be verified")
            }
        }

        context("skedulerHardDeleteSakByGrupperingsid") {
            test("only moves the deletion later, at the Oslo local time") {
                var body = ""
                var correlationId = ""
                client { request ->
                    body = (request.body as TextContent).text
                    correlationId = request.headers["X-Request-ID"].orEmpty()
                    respond(
                        """{"data":{"skedulerHardDeleteSakByGrupperingsid":{"__typename":"SkedulerHardDeleteSakVellykket"}}}""",
                        HttpStatusCode.OK,
                    )
                }.extendCase(extendRequest()) shouldBe ArbeidsgiverNotificationResponse.Published

                body shouldContain "skedulerHardDeleteSakByGrupperingsid"
                body shouldContain """"grupperingsid":"mote-1""""
                body shouldContain """"merkelapp":"Dialogmøte""""
                body shouldContain """"hardDelete":{"nyTid":{"den":"2026-12-01T12:00:00"},"strategi":"FORLENG"}"""
                correlationId shouldBe "event-2"
            }

            test("maps a missing case to a not-yet-available result") {
                mapOf(
                    "SakFinnesIkke" to ArbeidsgiverNotificationResponse.NotYetAvailable(NOT_FOUND + "SakFinnesIkke"),
                    "UgyldigMerkelapp" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMerkelapp"),
                    "UkjentProdusent" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentProdusent"),
                ).forEach { (typeName, expected) ->
                    client {
                        respond(
                            """{"data":{"skedulerHardDeleteSakByGrupperingsid":{"__typename":"$typeName"}}}""",
                            HttpStatusCode.OK,
                        )
                    }.extendCase(extendRequest()) shouldBe expected
                }
            }
        }

        context("nyStatusSakByGrupperingsid") {
            test("sends the status with the dispatch as idempotency key") {
                var body = ""
                client { request ->
                    body = (request.body as TextContent).text
                    respond(
                        """{"data":{"nyStatusSakByGrupperingsid":{"__typename":"NyStatusSakVellykket"}}}""",
                        HttpStatusCode.OK,
                    )
                }.setCaseStatus(ArbeidsgiversakStatusRequest("mote-1", "Dialogmøte", Saksstatus.FERDIG, "event-3")) shouldBe
                    ArbeidsgiverNotificationResponse.Published

                body shouldContain """"idempotencyKey":"event-3""""
                body shouldContain """"nyStatus":"FERDIG""""
                body shouldNotContain "hardDelete"
            }

            test("maps a missing case to a not-yet-available result") {
                mapOf(
                    "SakFinnesIkke" to ArbeidsgiverNotificationResponse.NotYetAvailable(NOT_FOUND + "SakFinnesIkke"),
                    "Konflikt" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "Konflikt"),
                ).forEach { (typeName, expected) ->
                    client {
                        respond("""{"data":{"nyStatusSakByGrupperingsid":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                    }.setCaseStatus(ArbeidsgiversakStatusRequest("mote-1", "Dialogmøte", Saksstatus.FERDIG, "event-3")) shouldBe expected
                }
            }
        }

        context("nyKalenderavtale") {
            test("sends Oslo times, place, state and one email per leader address in NKS hours") {
                var body = ""
                var correlationId = ""
                client { request ->
                    body = (request.body as TextContent).text
                    correlationId = request.headers["X-Request-ID"].orEmpty()
                    respond(
                        """{"data":{"nyKalenderavtale":{"__typename":"NyKalenderavtaleVellykket"}}}""",
                        HttpStatusCode.OK,
                    )
                }.createCalendarEvent(calendarRequest()) shouldBe ArbeidsgiverNotificationResponse.Published

                body shouldContain """"grupperingsid":"mote-1""""
                body shouldContain """"eksternId":"avtale-1""""
                body shouldContain """"startTidspunkt":"2026-11-02T10:00:00+01:00""""
                body shouldContain """"sluttTidspunkt":"2026-11-02T11:00:00+01:00""""
                body shouldContain """"lokasjon":{"adresse":"Gate 1","postnummer":"0001","poststed":"Oslo"}"""
                body shouldContain """"erDigitalt":true"""
                body shouldContain """"tilstand":"VENTER_SVAR_FRA_ARBEIDSGIVER""""
                body shouldContain """"epostadresse":"first@example.test""""
                body shouldContain """"epostadresse":"second@example.test""""
                body shouldContain """"sendevindu":"NKS_AAPNINGSTID""""
                correlationId shouldBe "event-4"
            }

            test("uses the summer-time offset for an event in summer") {
                var body = ""
                client { request ->
                    body = (request.body as TextContent).text
                    respond(
                        """{"data":{"nyKalenderavtale":{"__typename":"NyKalenderavtaleVellykket"}}}""",
                        HttpStatusCode.OK,
                    )
                }.createCalendarEvent(
                    calendarRequest().copy(startAt = Instant.parse("2026-06-15T08:00:00Z"), endAt = null),
                )

                body shouldContain """"startTidspunkt":"2026-06-15T10:00:00+02:00""""
            }

            test("sends an empty email list without an external notification") {
                var body = ""
                client { request ->
                    body = (request.body as TextContent).text
                    respond(
                        """{"data":{"nyKalenderavtale":{"__typename":"NyKalenderavtaleVellykket"}}}""",
                        HttpStatusCode.OK,
                    )
                }.createCalendarEvent(calendarRequest(email = false))

                body shouldContain """"eksterneVarsler":[]"""
            }

            test("maps every business result") {
                mapOf(
                    "SakFinnesIkke" to ArbeidsgiverNotificationResponse.NotYetAvailable(NOT_FOUND + "SakFinnesIkke"),
                    "UgyldigKalenderavtale" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigKalenderavtale"),
                    "UgyldigMerkelapp" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMerkelapp"),
                    "UgyldigMottaker" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMottaker"),
                    "UkjentProdusent" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentProdusent"),
                ).forEach { (typeName, expected) ->
                    client {
                        respond("""{"data":{"nyKalenderavtale":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                    }.createCalendarEvent(calendarRequest()) shouldBe expected
                }
            }
        }

        context("nyKalenderavtale duplicate") {
            fun duplicateThen(hentNotifikasjon: String) =
                client { request ->
                    val body = (request.body as TextContent).text
                    if (body.contains("hentNotifikasjon")) {
                        body shouldContain """"id":"avtale-123""""
                        respond("""{"data":{"hentNotifikasjon":$hentNotifikasjon}}""", HttpStatusCode.OK)
                    } else {
                        respond(
                            """{"data":{"nyKalenderavtale":{"__typename":"DuplikatEksternIdOgMerkelapp",""" +
                                """"idTilEksisterende":"avtale-123"}}}""",
                            HttpStatusCode.OK,
                        )
                    }
                }

            fun hentet(notifikasjon: String) = """{"__typename":"HentetNotifikasjon","notifikasjon":$notifikasjon}"""

            test("counts as created when the existing calendar event is in the same case") {
                duplicateThen(
                    hentet("""{"__typename":"Kalenderavtale","metadata":{"grupperingsid":"mote-1"}}"""),
                ).createCalendarEvent(calendarRequest()) shouldBe ArbeidsgiverNotificationResponse.Published
            }

            test("is rejected when the reference is used in another case") {
                duplicateThen(
                    hentet("""{"__typename":"Kalenderavtale","metadata":{"grupperingsid":"mote-2"}}"""),
                ).createCalendarEvent(calendarRequest()) shouldBe
                    ArbeidsgiverNotificationResponse.Rejected(
                        REJECTED + "DuplikatEksternIdOgMerkelapp for another case or notification type",
                    )
            }

            test("is rejected when the reference belongs to another notification type") {
                duplicateThen(hentet("""{"__typename":"Beskjed"}""")).createCalendarEvent(calendarRequest()) shouldBe
                    ArbeidsgiverNotificationResponse.Rejected(
                        REJECTED + "DuplikatEksternIdOgMerkelapp for another case or notification type",
                    )
            }

            test("is rejected when the existing event cannot be found") {
                duplicateThen("""{"__typename":"NotifikasjonFinnesIkke"}""").createCalendarEvent(calendarRequest()) shouldBe
                    ArbeidsgiverNotificationResponse.Rejected(
                        REJECTED + "DuplikatEksternIdOgMerkelapp that could not be verified",
                    )
            }
        }

        context("oppdaterKalenderavtaleByEksternId") {
            test("sends only the requested changes and tells which case the event lives in") {
                var body = ""
                var lookup = ""
                client { request ->
                    val text = (request.body as TextContent).text
                    if (text.contains("hentNotifikasjon")) {
                        lookup = text
                        respond(
                            """{"data":{"hentNotifikasjon":{"__typename":"HentetNotifikasjon","notifikasjon":""" +
                                """{"__typename":"Kalenderavtale","metadata":{"grupperingsid":"DIALOGMOTE:relasjon-1"}}}}}""",
                            HttpStatusCode.OK,
                        )
                    } else {
                        body = text
                        respond(
                            """{"data":{"oppdaterKalenderavtaleByEksternId":{"__typename":"OppdaterKalenderavtaleVellykket","id":"avtale-id"}}}""",
                            HttpStatusCode.OK,
                        )
                    }
                }.updateCalendarEvent(
                    KalenderavtaleUpdateRequest(
                        tag = "Dialogmøte",
                        eksternId = "avtale-1",
                        nyTilstand = Kalenderavtaletilstand.AVLYST,
                        nyTekst = null,
                        externalVarsling = null,
                        idempotencyKey = "event-3",
                    ),
                ) shouldBe KalenderavtaleUpdateResponse.Updated("DIALOGMOTE:relasjon-1")

                lookup shouldContain """"id":"avtale-id""""
                body shouldContain """"eksternId":"avtale-1""""
                body shouldContain """"nyTilstand":"AVLYST""""
                body shouldContain """"idempotencyKey":"event-3""""
                body shouldContain """"eksterneVarsler":[]"""
                body shouldNotContain "\"nyTekst\""
            }

            test("an update stays successful when the case lookup fails afterwards") {
                client { request ->
                    if ((request.body as TextContent).text.contains("hentNotifikasjon")) {
                        respond("", HttpStatusCode.InternalServerError)
                    } else {
                        respond(
                            """{"data":{"oppdaterKalenderavtaleByEksternId":{"__typename":"OppdaterKalenderavtaleVellykket","id":"avtale-id"}}}""",
                            HttpStatusCode.OK,
                        )
                    }
                }.updateCalendarEvent(
                    KalenderavtaleUpdateRequest("Dialogmøte", "avtale-1", Kalenderavtaletilstand.AVLYST, null, null, "event-3"),
                ) shouldBe KalenderavtaleUpdateResponse.Updated(null)
            }

            test("maps a calendar event that does not exist yet to a retryable result") {
                mapOf(
                    "NotifikasjonFinnesIkke" to
                        ArbeidsgiverNotificationResponse.NotYetAvailable(NOT_FOUND + "NotifikasjonFinnesIkke"),
                    "Konflikt" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "Konflikt"),
                    "UgyldigKalenderavtale" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigKalenderavtale"),
                    "UgyldigMerkelapp" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMerkelapp"),
                    "UkjentProdusent" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentProdusent"),
                ).forEach { (typeName, expected) ->
                    client {
                        respond(
                            """{"data":{"oppdaterKalenderavtaleByEksternId":{"__typename":"$typeName"}}}""",
                            HttpStatusCode.OK,
                        )
                    }.updateCalendarEvent(
                        KalenderavtaleUpdateRequest("Dialogmøte", "avtale-1", Kalenderavtaletilstand.AVLYST, null, null, "event-3"),
                    ) shouldBe KalenderavtaleUpdateResponse.NotUpdated(expected)
                }
            }
        }
    })

private val LEADER =
    ArbeidsgiverNotificationRecipient.NarmesteLeder(
        narmesteLederFnr = PersonIdentifier(LEADER_FNR),
        ansattFnr = PersonIdentifier("00000000000"),
    )

private fun caseRequest() =
    ArbeidsgiversakCreateRequest(
        virksomhetsnummer = "123456789",
        grupperingsid = "mote-1",
        tag = "Dialogmøte",
        tittel = "Dialogmøte med Test",
        lenke = "https://nav.no/syk/dinesykmeldte/relasjon-1",
        keepUntil = Instant.parse("2026-12-01T11:00:00Z"),
        recipient = LEADER,
        correlationId = "event-1",
    )

private fun extendRequest() =
    ArbeidsgiversakExtendRequest(
        grupperingsid = "mote-1",
        tag = "Dialogmøte",
        keepUntil = Instant.parse("2026-12-01T11:00:00Z"),
        correlationId = "event-2",
    )

private fun calendarRequest(email: Boolean = true) =
    KalenderavtaleCreateRequest(
        virksomhetsnummer = "123456789",
        grupperingsid = "mote-1",
        tag = "Dialogmøte",
        eksternId = "avtale-1",
        tekst = "Dialogmøte med Test",
        lenke = "https://nav.no/ag",
        startAt = Instant.parse("2026-11-02T09:00:00Z"),
        endAt = Instant.parse("2026-11-02T10:00:00Z"),
        lokasjon = Kalenderavtalelokasjon("Gate 1", "0001", "Oslo"),
        erDigitalt = true,
        tilstand = Kalenderavtaletilstand.VENTER_SVAR_FRA_ARBEIDSGIVER,
        recipient =
            LEADER.copy(
                externalVarsling =
                    if (email) {
                        NarmesteLederExternalVarsling(
                            epostTittel = "Tittel",
                            epostHtmlBody = "<p>E-post</p>",
                            epostadresser = listOf("first@example.test", "second@example.test"),
                        )
                    } else {
                        null
                    },
            ),
        correlationId = "event-4",
    )

private fun client(handler: MockRequestHandler) =
    ArbeidsgiverNotifikasjonClient(
        HttpClient(MockEngine { request -> handler(request) }),
        ArbeidsgiverNotifikasjonConfig(
            url = "https://ag-notifikasjon-produsent-api/api/graphql",
            scope = "api://dev-gcp.fager.notifikasjon-produsent-api/.default",
        ),
        object : TokenProvider {
            override suspend fun token(target: String) = "token"
        },
    )
