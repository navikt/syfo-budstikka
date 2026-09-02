package no.nav.budstikka.infrastructure.client

import com.apollographql.apollo.api.Optional
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import no.nav.budstikka.application.delivery.AltinnExternalVarsling
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.ArbeidsgiverNotifikasjonConfig
import no.nav.budstikka.infrastructure.client.fager.generated.NyBeskjedMutation
import no.nav.budstikka.infrastructure.client.fager.generated.type.MetadataInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.MottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NaermesteLederMottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NotifikasjonInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NyBeskjedInput
import kotlin.time.Instant

class ArbeidsgiverNotifikasjonClientTest :
    FunSpec({
        test("sends nyBeskjed with producer HTML, optional grouping id and Altinn external notification") {
            var body = ""
            var correlationId = ""
            var authorization = ""
            val client =
                client { request ->
                    body = (request.body as TextContent).text
                    correlationId = request.headers["X-Request-ID"].orEmpty()
                    authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                    respond("""{"data":{"nyBeskjed":{"__typename":"NyBeskjedVellykket","id":"1"}}}""", HttpStatusCode.OK)
                }

            client.publish(
                request(
                    tag = "producer-owned-tag",
                    groupingId = "sak-1",
                    externalVarsling =
                        AltinnExternalVarsling(
                            epostTittel = "Tittel <rå>",
                            epostHtmlBody = "<p>A &amp; <strong>B</strong></p>",
                            smsTekst = "SMS <rå>",
                        ),
                ),
            ) shouldBe ArbeidsgiverNotificationResponse.Published

            body shouldContain "nyBeskjed"
            body shouldContain """"input":{"mottakere":[{"altinnRessurs":{"ressursId":"producer-owned-resource"}}]"""
            body shouldContain """"merkelapp":"producer-owned-tag""""
            body shouldContain """"tekst":"Tekst""""
            body shouldContain """"lenke":"https://nav.no""""
            body shouldContain """"eksternId":"external-id""""
            body shouldContain """"grupperingsid":"sak-1""""
            body shouldContain """"hardDelete":{"om":"P4M"}"""
            body.contains("hardDelete\":{\"den\"") shouldBe false
            body shouldContain """"sendevindu":"LOEPENDE""""
            body shouldContain """"epostHtmlBody":"<p>A &amp; <strong>B</strong></p>""""
            body shouldContain """"epostTittel":"Tittel <rå>""""
            body shouldContain """"smsTekst":"SMS <rå>""""
            correlationId shouldBe "external-id"
            authorization shouldBe "Bearer token"
        }

        test("sends nyOppgave and omits null grouping id") {
            var body = ""
            val client =
                client { request ->
                    body = (request.body as TextContent).text
                    respond("""{"data":{"nyOppgave":{"__typename":"NyOppgaveVellykket"}}}""", HttpStatusCode.OK)
                }

            client.publish(request(meldingstype = ArbeidsgiverMeldingstype.OPPGAVE)) shouldBe ArbeidsgiverNotificationResponse.Published

            body shouldContain "nyOppgave"
            body shouldContain """"merkelapp":"Dialogmøte""""
            body shouldContain """"hardDelete":{"om":"P4M"}"""
            body.contains("hardDelete\":{\"den\"") shouldBe false
            body.contains("grupperingsid") shouldBe false
        }

        test("sends visibleUntil as a Europe Oslo hard-delete time without a fallback duration") {
            listOf(
                Instant.parse("2026-06-30T22:00:00.999Z") to "2026-07-01T00:00:00.999",
                Instant.parse("2026-01-01T10:15:30.999Z") to "2026-01-01T11:15:30.999",
            ).forEach { (visibleUntil, expectedHardDelete) ->
                var body = ""
                val client =
                    client { request ->
                        body = (request.body as TextContent).text
                        respond("""{"data":{"nyBeskjed":{"__typename":"NyBeskjedVellykket","id":"1"}}}""", HttpStatusCode.OK)
                    }

                client.publish(request(visibleUntil = visibleUntil)) shouldBe ArbeidsgiverNotificationResponse.Published

                body shouldContain """"hardDelete":{"den":"$expectedHardDelete"}"""
                body.contains("hardDelete\":{\"om\"") shouldBe false
            }
        }

        test("forwards external email HTML unchanged") {
            var body = ""
            val client =
                client { request ->
                    body = (request.body as TextContent).text
                    respond("""{"data":{"nyBeskjed":{"__typename":"NyBeskjedVellykket"}}}""", HttpStatusCode.OK)
                }

            client.publish(
                request(
                    externalVarsling =
                        AltinnExternalVarsling(
                            epostTittel = "Tittel",
                            epostHtmlBody = "<p>Før</p>\r\n<p><strong>Etter</strong></p>",
                            smsTekst = "SMS",
                        ),
                ),
            ) shouldBe ArbeidsgiverNotificationResponse.Published

            body shouldContain """"epostHtmlBody":"<p>Før</p>\r\n<p><strong>Etter</strong></p>""""
        }

        test("sends NarmesteLeder recipient and one email notification per address") {
            var body = ""
            val client =
                client { request ->
                    body = (request.body as TextContent).text
                    respond("""{"data":{"nyBeskjed":{"__typename":"NyBeskjedVellykket"}}}""", HttpStatusCode.OK)
                }

            client.publish(
                request(
                    recipient =
                        ArbeidsgiverNotificationRecipient.NarmesteLeder(
                            narmesteLederFnr = PersonIdentifier("00000000000"),
                            ansattFnr = PersonIdentifier("00000000000"),
                            externalVarsling =
                                no.nav.budstikka.application.delivery.NarmesteLederExternalVarsling(
                                    "Tittel",
                                    "<p>A &amp; <strong>B</strong></p>",
                                    listOf("first@example.test", "second@example.test"),
                                ),
                        ),
                ),
            ) shouldBe ArbeidsgiverNotificationResponse.Published

            body shouldContain """"naermesteLeder":{"naermesteLederFnr":"00000000000","ansattFnr":"00000000000"}"""
            body shouldContain """"virksomhetsnummer":"123456789""""
            body shouldContain """"epostadresse":"first@example.test""""
            body shouldContain """"epostadresse":"second@example.test""""
            body shouldContain """"epostHtmlBody":"<p>A &amp; <strong>B</strong></p>""""
            body shouldContain """"sendevindu":"LOEPENDE""""
            body.contains("altinnRessurs") shouldBe false
        }

        test("maps every nyBeskjed business result") {
            val expected =
                mapOf(
                    "DuplikatEksternIdOgMerkelapp" to
                        ArbeidsgiverNotificationResponse.Rejected(
                            "Arbeidsgiver notification API rejected request: DuplikatEksternIdOgMerkelapp",
                        ),
                    "UgyldigMerkelapp" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UgyldigMerkelapp"),
                    "UgyldigMottaker" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UgyldigMottaker"),
                    "UkjentProdusent" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UkjentProdusent"),
                    "UkjentRolle" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UkjentRolle"),
                )
            expected.forEach { (typeName, response) ->
                client {
                    respond("""{"data":{"nyBeskjed":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                }.publish(request()) shouldBe response
            }
        }

        test("maps every nyOppgave business result") {
            val expected =
                mapOf(
                    "DuplikatEksternIdOgMerkelapp" to
                        ArbeidsgiverNotificationResponse.Rejected(
                            "Arbeidsgiver notification API rejected request: DuplikatEksternIdOgMerkelapp",
                        ),
                    "UgyldigMerkelapp" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UgyldigMerkelapp"),
                    "UgyldigMottaker" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UgyldigMottaker"),
                    "UkjentProdusent" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UkjentProdusent"),
                    "UkjentRolle" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UkjentRolle"),
                    "UgyldigPaaminnelseTidspunkt" to
                        ArbeidsgiverNotificationResponse.Rejected(
                            "Arbeidsgiver notification API rejected request: UgyldigPaaminnelseTidspunkt",
                        ),
                )
            expected.forEach { (typeName, response) ->
                client {
                    respond("""{"data":{"nyOppgave":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                }.publish(request(meldingstype = ArbeidsgiverMeldingstype.OPPGAVE)) shouldBe response
            }
        }

        test("generated Apollo request models omit payloads from toString") {
            val sensitiveText = "sensitive notification content"
            val input =
                NyBeskjedInput(
                    notifikasjon = NotifikasjonInput("Dialogmøte", sensitiveText, "https://nav.no/sensitive"),
                    metadata = MetadataInput("123456789", "sensitive-external-id"),
                )
            val recipient =
                NaermesteLederMottakerInput(
                    naermesteLederFnr = "00000000000",
                    ansattFnr = "00000000000",
                )
            val wrappedRecipient =
                MottakerInput(
                    naermesteLeder = Optional.present(recipient),
                )

            listOf(input, NyBeskjedMutation(input), recipient, wrappedRecipient).forEach {
                it.toString() shouldNotContain sensitiveText
                it.toString() shouldNotContain "sensitive-external-id"
                it.toString() shouldNotContain "00000000000"
            }
        }

        test("maps 400 to Rejected without including the response body") {
            client {
                respond("""{"message":"consumer text must not escape"}""", HttpStatusCode.BadRequest)
            }.publish(request()) shouldBe
                ArbeidsgiverNotificationResponse.Rejected(
                    "Arbeidsgiver notification API rejected request with status 400",
                )
        }

        test("throws for retry on 401, 403, 404, 5xx and GraphQL transport errors") {
            listOf(
                HttpStatusCode.Unauthorized,
                HttpStatusCode.Forbidden,
                HttpStatusCode.NotFound,
                HttpStatusCode.InternalServerError,
            ).forEach { status ->
                shouldThrow<IllegalStateException> {
                    client { respond("""{"errors":[{"message":"sensitive"}]}""", status) }.publish(request())
                }
            }
            shouldThrow<IllegalStateException> {
                client { respond("""{"errors":[{"message":"sensitive"}]}""", HttpStatusCode.OK) }.publish(request())
            }
        }

        test("throws for retry when the GraphQL union contains an unknown result type") {
            shouldThrow<IllegalStateException> {
                client {
                    respond(
                        """{"data":{"nyBeskjed":{"__typename":"NyttUkjentResultat"}}}""",
                        HttpStatusCode.OK,
                    )
                }.publish(request())
            }
        }

        test("sanitizes malformed Apollo responses") {
            listOf(
                "",
                """{"data":{"nyBeskjed":null}}""",
                """{"data":{"nyBeskjed":{}}}""",
            ).forEach { responseBody ->
                val error =
                    shouldThrow<IllegalStateException> {
                        client { respond(responseBody, HttpStatusCode.OK) }.publish(request())
                    }
                error.message shouldBe "Arbeidsgiver notification API returned an invalid response"
            }
        }
    })

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

private fun request(
    tag: String = "Dialogmøte",
    groupingId: String? = null,
    meldingstype: ArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype.BESKJED,
    visibleUntil: Instant? = null,
    externalVarsling: AltinnExternalVarsling? = null,
    recipient: ArbeidsgiverNotificationRecipient =
        ArbeidsgiverNotificationRecipient.AltinnRessurs("producer-owned-resource", externalVarsling),
) = ArbeidsgiverNotificationRequest(
    virksomhetsnummer = "123456789",
    eksternId = "external-id",
    grupperingsid = groupingId,
    tag = tag,
    tekst = "Tekst",
    lenke = "https://nav.no",
    recipient = recipient,
    meldingstype = meldingstype,
    visibleUntil = visibleUntil,
)
