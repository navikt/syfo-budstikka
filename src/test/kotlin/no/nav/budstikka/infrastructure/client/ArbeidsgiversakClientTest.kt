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
import no.nav.budstikka.application.delivery.ArbeidsgiversakStatusRequest
import no.nav.budstikka.application.delivery.KalenderavtaleCreateRequest
import no.nav.budstikka.application.delivery.KalenderavtaleUpdateRequest
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
            test("sends case identity, leader recipient, Oslo hard delete and correlation id") {
                var body = ""
                var correlationId = ""
                val client =
                    client { request ->
                        body = (request.body as TextContent).text
                        correlationId = request.headers["X-Request-ID"].orEmpty()
                        respond("""{"data":{"nySak":{"__typename":"NySakVellykket","id":"1"}}}""", HttpStatusCode.OK)
                    }

                client.createCase(caseRequest(hardDeleteAt = Instant.parse("2026-12-01T11:00:00Z"))) shouldBe
                    ArbeidsgiverNotificationResponse.Published

                body shouldContain "nySak"
                body shouldContain """"grupperingsid":"mote-1""""
                body shouldContain """"merkelapp":"Dialogmøte""""
                body shouldContain """"virksomhetsnummer":"123456789""""
                body shouldContain """"naermesteLederFnr":"$LEADER_FNR","ansattFnr":"00000000000""""
                body shouldContain """"tittel":"Dialogmøte med Test""""
                body shouldContain """"initiellStatus":"MOTTATT""""
                body shouldContain """"hardDelete":{"den":"2026-12-01T12:00:00"}"""
                body shouldNotContain "\"lenke\""
                correlationId shouldBe "event-1"
            }

            test("falls back to four months when no hard delete is given") {
                var body = ""
                client { request ->
                    body = (request.body as TextContent).text
                    respond("""{"data":{"nySak":{"__typename":"NySakVellykket","id":"1"}}}""", HttpStatusCode.OK)
                }.createCase(caseRequest())

                body shouldContain """"hardDelete":{"om":"P4M"}"""
            }

            test("maps every business result") {
                mapOf(
                    "DuplikatGrupperingsid" to ArbeidsgiverNotificationResponse.Published,
                    "DuplikatGrupperingsidEtterDelete" to
                        ArbeidsgiverNotificationResponse.Rejected(REJECTED + "DuplikatGrupperingsidEtterDelete"),
                    "UgyldigMerkelapp" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMerkelapp"),
                    "UgyldigMottaker" to ArbeidsgiverNotificationResponse.InvalidRecipient(REJECTED + "UgyldigMottaker"),
                    "UkjentProdusent" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentProdusent"),
                    "UkjentRolle" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentRolle"),
                ).forEach { (typeName, expected) ->
                    client {
                        respond("""{"data":{"nySak":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                    }.createCase(caseRequest()) shouldBe expected
                }
            }
        }

        context("nyStatusSakByGrupperingsid") {
            test("sends status, status text, overwrite hard delete and the dispatch as idempotency key") {
                var body = ""
                client { request ->
                    body = (request.body as TextContent).text
                    respond(
                        """{"data":{"nyStatusSakByGrupperingsid":{"__typename":"NyStatusSakVellykket"}}}""",
                        HttpStatusCode.OK,
                    )
                }.updateCaseStatus(
                    ArbeidsgiversakStatusRequest(
                        grupperingsid = "mote-1",
                        tag = "Dialogmøte",
                        status = Saksstatus.FERDIG,
                        statustekst = "Avlyst",
                        hardDeleteAt = Instant.parse("2026-12-01T11:00:00Z"),
                        idempotencyKey = "event-2",
                    ),
                ) shouldBe ArbeidsgiverNotificationResponse.Published

                body shouldContain """"idempotencyKey":"event-2""""
                body shouldContain """"grupperingsid":"mote-1""""
                body shouldContain """"nyStatus":"FERDIG""""
                body shouldContain """"overstyrStatustekstMed":"Avlyst""""
                body shouldContain """"hardDelete":{"nyTid":{"den":"2026-12-01T12:00:00"},"strategi":"OVERSKRIV"}"""
                body shouldNotContain "\"tidspunkt\""
            }

            test("maps a missing case to a retryable result") {
                mapOf(
                    "SakFinnesIkke" to ArbeidsgiverNotificationResponse.NotYetAvailable(NOT_FOUND + "SakFinnesIkke"),
                    "Konflikt" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "Konflikt"),
                    "UgyldigMerkelapp" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMerkelapp"),
                    "UkjentProdusent" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentProdusent"),
                ).forEach { (typeName, expected) ->
                    client {
                        respond("""{"data":{"nyStatusSakByGrupperingsid":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                    }.updateCaseStatus(
                        ArbeidsgiversakStatusRequest("mote-1", "Dialogmøte", Saksstatus.FERDIG, null, null, "event-2"),
                    ) shouldBe expected
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
                correlationId shouldBe "avtale-1"
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
                    "DuplikatEksternIdOgMerkelapp" to ArbeidsgiverNotificationResponse.Published,
                    "UgyldigKalenderavtale" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigKalenderavtale"),
                    "UgyldigMerkelapp" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMerkelapp"),
                    "UgyldigMottaker" to ArbeidsgiverNotificationResponse.InvalidRecipient(REJECTED + "UgyldigMottaker"),
                    "UkjentProdusent" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentProdusent"),
                ).forEach { (typeName, expected) ->
                    client {
                        respond("""{"data":{"nyKalenderavtale":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                    }.createCalendarEvent(calendarRequest()) shouldBe expected
                }
            }
        }

        context("oppdaterKalenderavtaleByEksternId") {
            test("sends only the requested changes and the dispatch as idempotency key") {
                var body = ""
                client { request ->
                    body = (request.body as TextContent).text
                    respond(
                        """{"data":{"oppdaterKalenderavtaleByEksternId":{"__typename":"OppdaterKalenderavtaleVellykket"}}}""",
                        HttpStatusCode.OK,
                    )
                }.updateCalendarEvent(
                    KalenderavtaleUpdateRequest(
                        tag = "Dialogmøte",
                        eksternId = "avtale-1",
                        nyTilstand = Kalenderavtaletilstand.AVLYST,
                        nyTekst = null,
                        externalVarsling = null,
                        idempotencyKey = "event-3",
                    ),
                ) shouldBe ArbeidsgiverNotificationResponse.Published

                body shouldContain """"eksternId":"avtale-1""""
                body shouldContain """"nyTilstand":"AVLYST""""
                body shouldContain """"idempotencyKey":"event-3""""
                body shouldContain """"eksterneVarsler":[]"""
                body shouldNotContain "\"nyTekst\""
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
                    ) shouldBe expected
                }
            }
        }
    })

private val LEADER =
    ArbeidsgiverNotificationRecipient.NarmesteLeder(
        narmesteLederFnr = PersonIdentifier(LEADER_FNR),
        ansattFnr = PersonIdentifier("00000000000"),
    )

private fun caseRequest(hardDeleteAt: Instant? = null) =
    ArbeidsgiversakCreateRequest(
        virksomhetsnummer = "123456789",
        grupperingsid = "mote-1",
        tag = "Dialogmøte",
        tittel = "Dialogmøte med Test",
        lenke = null,
        initiellStatus = Saksstatus.MOTTATT,
        hardDeleteAt = hardDeleteAt,
        recipient = LEADER,
        correlationId = "event-1",
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
