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
            test("sends sak identity, leader recipient, link, Oslo hard delete and correlation id") {
                var body = ""
                var correlationId = ""
                val client =
                    client { request ->
                        body = (request.body as TextContent).text
                        correlationId = request.headers["X-Request-ID"].orEmpty()
                        respond("""{"data":{"nySak":{"__typename":"NySakVellykket","id":"1"}}}""", HttpStatusCode.OK)
                    }

                client.createSak(sakRequest()) shouldBe ArbeidsgiverNotificationResponse.Published

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
                        ArbeidsgiverNotificationResponse.SakDeleted(REJECTED + "DuplikatGrupperingsidEtterDelete"),
                    "UgyldigMerkelapp" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMerkelapp"),
                    "UgyldigMottaker" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UgyldigMottaker"),
                    "UkjentProdusent" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentProdusent"),
                    "UkjentRolle" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "UkjentRolle"),
                ).forEach { (typeName, expected) ->
                    client {
                        respond("""{"data":{"nySak":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                    }.createSak(sakRequest()) shouldBe expected
                }
            }
        }

        context("nySak duplicate") {
            fun duplicateThen(existing: String) =
                client { request ->
                    val body = (request.body as TextContent).text
                    if (body.contains("hentSak")) {
                        body shouldContain """"id":"sak-123""""
                        respond("""{"data":{"hentSak":$existing}}""", HttpStatusCode.OK)
                    } else {
                        respond(
                            """{"data":{"nySak":{"__typename":"DuplikatGrupperingsid","idTilEksisterende":"sak-123"}}}""",
                            HttpStatusCode.OK,
                        )
                    }
                }

            test("counts as created when the existing sak belongs to the same organisation") {
                duplicateThen(
                    """{"__typename":"HentetSak","sak":{"virksomhetsnummer":"123456789","grupperingsid":"mote-1"}}""",
                ).createSak(sakRequest()) shouldBe ArbeidsgiverNotificationResponse.Published
            }

            test("is rejected when the sak id is used by another organisation") {
                duplicateThen(
                    """{"__typename":"HentetSak","sak":{"virksomhetsnummer":"987654321","grupperingsid":"mote-1"}}""",
                ).createSak(sakRequest()) shouldBe
                    ArbeidsgiverNotificationResponse.Rejected(REJECTED + "DuplikatGrupperingsid in another organisation")
            }

            test("is rejected when the existing sak cannot be found") {
                duplicateThen("""{"__typename":"SakFinnesIkke"}""").createSak(sakRequest()) shouldBe
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
                }.extendSak(extendRequest()) shouldBe ArbeidsgiverNotificationResponse.Published

                body shouldContain "skedulerHardDeleteSakByGrupperingsid"
                body shouldContain """"grupperingsid":"mote-1""""
                body shouldContain """"merkelapp":"Dialogmøte""""
                body shouldContain """"hardDelete":{"nyTid":{"den":"2026-12-01T12:00:00"},"strategi":"FORLENG"}"""
                correlationId shouldBe "event-2"
            }

            test("maps a missing sak to a not-yet-available result") {
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
                    }.extendSak(extendRequest()) shouldBe expected
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
                }.setSaksstatus(ArbeidsgiversakStatusRequest("mote-1", "Dialogmøte", Saksstatus.FERDIG, "event-3")) shouldBe
                    ArbeidsgiverNotificationResponse.Published

                body shouldContain """"idempotencyKey":"event-3""""
                body shouldContain """"nyStatus":"FERDIG""""
                body shouldNotContain "hardDelete"
            }

            test("maps a missing sak to a not-yet-available result") {
                mapOf(
                    "SakFinnesIkke" to ArbeidsgiverNotificationResponse.NotYetAvailable(NOT_FOUND + "SakFinnesIkke"),
                    "Konflikt" to ArbeidsgiverNotificationResponse.Rejected(REJECTED + "Konflikt"),
                ).forEach { (typeName, expected) ->
                    client {
                        respond("""{"data":{"nyStatusSakByGrupperingsid":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                    }.setSaksstatus(ArbeidsgiversakStatusRequest("mote-1", "Dialogmøte", Saksstatus.FERDIG, "event-3")) shouldBe expected
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
                }.createKalenderavtale(kalenderavtaleRequest()) shouldBe ArbeidsgiverNotificationResponse.Published

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
                }.createKalenderavtale(
                    kalenderavtaleRequest().copy(startAt = Instant.parse("2026-06-15T08:00:00Z"), endAt = null),
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
                }.createKalenderavtale(kalenderavtaleRequest(email = false))

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
                    }.createKalenderavtale(kalenderavtaleRequest()) shouldBe expected
                }
            }
        }

        context("nyKalenderavtale duplicate") {
            fun duplicateThen(existing: String) =
                client { request ->
                    val body = (request.body as TextContent).text
                    if (body.contains("hentNotifikasjon")) {
                        body shouldContain """"id":"avtale-123""""
                        respond("""{"data":{"hentNotifikasjon":$existing}}""", HttpStatusCode.OK)
                    } else {
                        respond(
                            """{"data":{"nyKalenderavtale":{"__typename":"DuplikatEksternIdOgMerkelapp",""" +
                                """"idTilEksisterende":"avtale-123"}}}""",
                            HttpStatusCode.OK,
                        )
                    }
                }

            fun fetched(notifikasjon: String) = """{"__typename":"HentetNotifikasjon","notifikasjon":$notifikasjon}"""

            test("counts as created when the existing kalenderavtale is in the same sak") {
                duplicateThen(
                    fetched("""{"__typename":"Kalenderavtale","metadata":{"grupperingsid":"mote-1"}}"""),
                ).createKalenderavtale(kalenderavtaleRequest()) shouldBe ArbeidsgiverNotificationResponse.Published
            }

            test("is rejected when the reference is used in another sak") {
                duplicateThen(
                    fetched("""{"__typename":"Kalenderavtale","metadata":{"grupperingsid":"mote-2"}}"""),
                ).createKalenderavtale(kalenderavtaleRequest()) shouldBe
                    ArbeidsgiverNotificationResponse.Rejected(
                        REJECTED + "DuplikatEksternIdOgMerkelapp for another sak or notification type",
                    )
            }

            test("is rejected when the reference belongs to another notification type") {
                duplicateThen(fetched("""{"__typename":"Beskjed"}""")).createKalenderavtale(kalenderavtaleRequest()) shouldBe
                    ArbeidsgiverNotificationResponse.Rejected(
                        REJECTED + "DuplikatEksternIdOgMerkelapp for another sak or notification type",
                    )
            }

            test("is rejected when the existing event cannot be found") {
                duplicateThen("""{"__typename":"NotifikasjonFinnesIkke"}""").createKalenderavtale(kalenderavtaleRequest()) shouldBe
                    ArbeidsgiverNotificationResponse.Rejected(
                        REJECTED + "DuplikatEksternIdOgMerkelapp that could not be verified",
                    )
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
                }.updateKalenderavtale(
                    KalenderavtaleUpdateRequest(
                        tag = "Dialogmøte",
                        eksternId = "avtale-1",
                        tilstand = Kalenderavtaletilstand.AVLYST,
                        text = null,
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

            test("maps a kalenderavtale that does not exist yet to a retryable result") {
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
                    }.updateKalenderavtale(
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

private fun sakRequest() =
    ArbeidsgiversakCreateRequest(
        virksomhetsnummer = "123456789",
        grupperingsid = "mote-1",
        tag = "Dialogmøte",
        title = "Dialogmøte med Test",
        link = "https://nav.no/syk/dinesykmeldte/relasjon-1",
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

private fun kalenderavtaleRequest(email: Boolean = true) =
    KalenderavtaleCreateRequest(
        virksomhetsnummer = "123456789",
        grupperingsid = "mote-1",
        tag = "Dialogmøte",
        eksternId = "avtale-1",
        text = "Dialogmøte med Test",
        link = "https://nav.no/ag",
        startAt = Instant.parse("2026-11-02T09:00:00Z"),
        endAt = Instant.parse("2026-11-02T10:00:00Z"),
        lokasjon = Kalenderavtalelokasjon("Gate 1", "0001", "Oslo"),
        digital = true,
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
    ArbeidsgiversakClient(
        HttpClient(MockEngine { request -> handler(request) }),
        ArbeidsgiverNotifikasjonConfig(
            url = "https://ag-notifikasjon-produsent-api/api/graphql",
            scope = "api://dev-gcp.fager.notifikasjon-produsent-api/.default",
        ),
        object : TokenProvider {
            override suspend fun token(target: String) = "token"
        },
    )
