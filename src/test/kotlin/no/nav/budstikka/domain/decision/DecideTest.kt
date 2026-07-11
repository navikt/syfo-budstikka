package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.DittSykefravaerCreate
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.MicrofrontendDisable
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.NarmesteLeder
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Tag
import no.nav.budstikka.domain.dispatch.Varseltype
import java.util.UUID

class DecideTest :
    FunSpec({
        val sykmeldt = PersonIdentifier("11111111111")
        val orgnr = Orgnummer("987654321")

        fun envelope(content: DispatchContent) = Dispatch(eventId = UUID.randomUUID(), reference = "ref-1", content = content)

        val brukervarsel = BrukervarselCreate(sykmeldt, Varseltype.OPPGAVE, "text")

        context("død-gate") {
            val gatedCreates =
                listOf(
                    "Brukervarsel" to brukervarsel,
                    "DittSykefravaer" to DittSykefravaerCreate(sykmeldt, "text"),
                    "Brev" to BrevCreate(sykmeldt, "jp-1"),
                )

            gatedCreates.forEach { (name, content) ->
                test("user-facing CREATE ($name) for dead person is dropped with DEAD") {
                    val beslutning = decide(envelope(content), DecisionFoundation(recipientIsDead = true))
                    beslutning shouldBe Decision.Dropped(DropReason.DEAD)
                }
            }

            test("alive person returns Processed") {
                val beslutning = decide(envelope(brukervarsel), DecisionFoundation(recipientIsDead = false))
                beslutning.shouldBeInstanceOf<Decision.Processed>().deliveries shouldHaveSize 1
            }

            test("close operation (INACTIVATE) is not gated even when recipient is dead") {
                val inactivate = BrukervarselInactivate(reference = "ref-1", sykmeldt = sykmeldt)
                decide(envelope(inactivate), DecisionFoundation(recipientIsDead = true))
                    .shouldBeInstanceOf<Decision.Processed>()
            }

            test("microfrontend disable is not gated even when recipient is dead") {
                val disable = MicrofrontendDisable(personIdentifier = sykmeldt, mikrofrontendId = "mf-1")
                decide(envelope(disable), DecisionFoundation(recipientIsDead = true))
                    .shouldBeInstanceOf<Decision.Processed>()
            }

            test("microfrontend enable is not gated even when recipient is dead") {
                val enable = MicrofrontendEnable(personIdentifier = sykmeldt, mikrofrontendId = "mf-1")
                decide(envelope(enable), DecisionFoundation(recipientIsDead = true))
                    .shouldBeInstanceOf<Decision.Processed>()
            }

            test("leader notification is not gated on the employee's death (recipient is the leader)") {
                val ledervarsel = LedervarselCreate(sykmeldt = sykmeldt, orgnummer = orgnr, text = "text")
                decide(envelope(ledervarsel), DecisionFoundation(recipientIsDead = true))
                    .shouldBeInstanceOf<Decision.Processed>()
            }
        }

        context("ruting til channel/operation/recipient") {
            data class Case(
                val name: String,
                val content: DispatchContent,
                val channel: Channel,
                val operation: Operation,
                val recipient: Recipient,
            )

            val cases =
                listOf(
                    Case("Brukervarsel", brukervarsel, Channel.BRUKERVARSEL, Operation.CREATE, Recipient.Person(sykmeldt)),
                    Case(
                        "DittSykefravaer",
                        DittSykefravaerCreate(sykmeldt, "text"),
                        Channel.DITT_SYKEFRAVAER,
                        Operation.CREATE,
                        Recipient.Person(sykmeldt),
                    ),
                    Case(
                        "Brev",
                        BrevCreate(sykmeldt, "jp-1"),
                        Channel.BREV,
                        Operation.CREATE,
                        Recipient.Person(sykmeldt),
                    ),
                    Case(
                        "MicrofrontendEnable",
                        MicrofrontendEnable(sykmeldt, "mf-1"),
                        Channel.MICROFRONTEND,
                        Operation.CREATE,
                        Recipient.Person(sykmeldt),
                    ),
                    Case(
                        "Ledervarsel",
                        LedervarselCreate(sykmeldt, orgnr, "text"),
                        Channel.LEDERVARSEL,
                        Operation.CREATE,
                        Recipient.Person(sykmeldt),
                    ),
                    Case(
                        "Arbeidsgivervarsel",
                        ArbeidsgivervarselCreate(
                            orgnummer = orgnr,
                            recipient = NarmesteLeder(sykmeldt),
                            tag = Tag.DIALOGMOETE,
                            text = "text",
                            link = "https://nav.no",
                        ),
                        Channel.ARBEIDSGIVERVARSEL,
                        Operation.CREATE,
                        Recipient.Virksomhet(orgnr),
                    ),
                    Case(
                        "BrukervarselInactivate",
                        BrukervarselInactivate("ref-1", sykmeldt),
                        Channel.BRUKERVARSEL,
                        Operation.INACTIVATE,
                        Recipient.Person(sykmeldt),
                    ),
                    Case(
                        "MicrofrontendDisable",
                        MicrofrontendDisable(sykmeldt, "mf-1"),
                        Channel.MICROFRONTEND,
                        Operation.INACTIVATE,
                        Recipient.Person(sykmeldt),
                    ),
                )

            cases.forEach { case ->
                test("${case.name} → ${case.channel}/${case.operation}") {
                    val leveranse =
                        decide(envelope(case.content), DecisionFoundation())
                            .shouldBeInstanceOf<Decision.Processed>()
                            .deliveries
                            .single()
                    leveranse.channel shouldBe case.channel
                    leveranse.operation shouldBe case.operation
                    leveranse.recipient shouldBe case.recipient
                    leveranse.reference shouldBe "ref-1"
                    leveranse.content shouldBe case.content
                }
            }
        }
    })
