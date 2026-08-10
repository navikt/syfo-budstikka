package no.nav.budstikka.infrastructure.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import no.nav.budstikka.application.port.AltinnExternalVarsling
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationResponse
import no.nav.budstikka.contract.AltinnResourceId
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Tag
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.ArbeidsgiverNotifikasjonConfig

class ArbeidsgiverNotifikasjonClientTest :
    FunSpec({
        val config =
            ArbeidsgiverNotifikasjonConfig(
                url = "https://ag-notifikasjon-produsent-api/api/graphql",
                scope = "api://dev-gcp.fager.notifikasjon-produsent-api/.default",
            )

        test("sends nyBeskjed with mapped wire values, optional grouping id and Altinn external notification") {
            var body = ""
            var callId = ""
            val client =
                client { request ->
                    body = (request.body as TextContent).text
                    callId = request.headers["Nav-Call-Id"].orEmpty()
                    respond("""{"data":{"nyBeskjed":{"__typename":"NyBeskjedVellykket","id":"1"}}}""", HttpStatusCode.OK)
                }

            client.publish(
                request(
                    tag = Tag.OPPFOELGING,
                    groupingId = "sak-1",
                    externalVarsling =
                        AltinnExternalVarsling(
                            epostTittel = "Tittel <rå>",
                            epostTekst = "A & <B>\n\"C\" 'D'",
                            smsTekst = "SMS <rå>",
                        ),
                ),
            ) shouldBe ArbeidsgiverNotificationResponse.Published

            body shouldContain "nyBeskjed"
            body shouldContain """"nyBeskjed":{"mottakere":[{"altinnRessurs":{"ressursId":"nav_syfo_dialogmote"}}]"""
            body shouldContain """"merkelapp":"Oppfølging""""
            body shouldContain """"grupperingsid":"sak-1""""
            body shouldContain """"sendevindu":"LOEPENDE""""
            body shouldContain """"epostHtmlBody":"A &amp; &lt;B&gt;<br>&quot;C&quot; &#39;D&#39;""""
            body shouldContain """"epostTittel":"Tittel <rå>""""
            body shouldContain """"smsTekst":"SMS <rå>""""
            callId shouldBe "external-id"
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
            body.contains("grupperingsid") shouldBe false
        }

        test("normalizes CRLF and CR before escaping external email text") {
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
                            epostTekst = "før\r\netter\ralene",
                            smsTekst = "SMS",
                        ),
                ),
            ) shouldBe ArbeidsgiverNotificationResponse.Published

            body shouldContain """"epostHtmlBody":"før<br>etter<br>alene""""
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
                                no.nav.budstikka.application.port.NarmesteLederExternalVarsling(
                                    "Tittel",
                                    "A & <B>",
                                    listOf("first@example.test", "second@example.test"),
                                ),
                        ),
                ),
            ) shouldBe ArbeidsgiverNotificationResponse.Published

            body shouldContain """"naermesteLeder":{"naermesteLederFnr":"00000000000","ansattFnr":"00000000000"}"""
            body shouldContain """"virksomhetsnummer":"123456789""""
            body shouldContain """"epostadresse":"first@example.test""""
            body shouldContain """"epostadresse":"second@example.test""""
            body shouldContain """"epostHtmlBody":"A &amp; &lt;B&gt;""""
            body shouldContain """"sendevindu":"LOEPENDE""""
            body.contains("altinnRessurs") shouldBe false
        }

        test("maps duplicate to Published and documented business errors to Rejected") {
            val expected =
                mapOf(
                    "DuplikatEksternIdOgMerkelapp" to ArbeidsgiverNotificationResponse.Published,
                    "UgyldigMerkelapp" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UgyldigMerkelapp"),
                    "UgyldigMottaker" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UgyldigMottaker"),
                    "UkjentProdusent" to
                        ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: UkjentProdusent"),
                )
            expected.forEach { (typeName, response) ->
                client {
                    respond("""{"data":{"nyBeskjed":{"__typename":"$typeName"}}}""", HttpStatusCode.OK)
                }.publish(request()) shouldBe response
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
    tag: Tag = Tag.DIALOGMOETE,
    groupingId: String? = null,
    meldingstype: ArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype.BESKJED,
    externalVarsling: AltinnExternalVarsling? = null,
    recipient: ArbeidsgiverNotificationRecipient =
        ArbeidsgiverNotificationRecipient.AltinnRessurs(AltinnResourceId.DIALOGMOETE, externalVarsling),
) = ArbeidsgiverNotificationRequest(
    virksomhetsnummer = "123456789",
    eksternId = "external-id",
    grupperingsid = groupingId,
    tag = tag,
    tekst = "Tekst",
    lenke = "https://nav.no",
    recipient = recipient,
    meldingstype = meldingstype,
)
