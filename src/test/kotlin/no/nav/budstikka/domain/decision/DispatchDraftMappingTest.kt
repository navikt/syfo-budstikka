package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
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

/**
 * Ren type→kanal/operasjon/mottaker-mapping ([toDeliveryDraft]). Ingen I/O, ingen gater – gate-
 * oppførselen ligger i [DeathGateTest]/[DecisionProcessTest].
 */
class DispatchDraftMappingTest :
    FunSpec({
        val sykmeldt = PersonIdentifier("11111111111")
        val orgnr = Orgnummer("987654321")

        data class Case(
            val name: String,
            val content: DispatchContent,
            val channel: Channel,
            val operation: Operation,
            val recipient: Recipient,
        )

        val cases =
            listOf(
                Case(
                    "Brukervarsel",
                    BrukervarselCreate(sykmeldt, Varseltype.OPPGAVE, "text"),
                    Channel.BRUKERVARSEL,
                    Operation.CREATE,
                    Recipient.Person(sykmeldt),
                ),
                Case(
                    "BrukervarselInactivate",
                    BrukervarselInactivate("ref-1", sykmeldt),
                    Channel.BRUKERVARSEL,
                    Operation.INACTIVATE,
                    Recipient.Person(sykmeldt),
                ),
                Case(
                    "DittSykefravaer",
                    DittSykefravaerCreate(sykmeldt, "text"),
                    Channel.DITT_SYKEFRAVAER,
                    Operation.CREATE,
                    Recipient.Person(sykmeldt),
                ),
                Case("Brev", BrevCreate(sykmeldt, "jp-1"), Channel.BREV, Operation.CREATE, Recipient.Person(sykmeldt)),
                Case(
                    "MicrofrontendEnable",
                    MicrofrontendEnable(sykmeldt, "mf-1"),
                    Channel.MICROFRONTEND,
                    Operation.CREATE,
                    Recipient.Person(sykmeldt),
                ),
                Case(
                    "MicrofrontendDisable",
                    MicrofrontendDisable(sykmeldt, "mf-1"),
                    Channel.MICROFRONTEND,
                    Operation.INACTIVATE,
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
            )

        cases.forEach { case ->
            test("${case.name} -> ${case.channel}/${case.operation}") {
                val draft = case.content.toDeliveryDraft("ref-1")
                draft.channel shouldBe case.channel
                draft.operation shouldBe case.operation
                draft.recipient shouldBe case.recipient
                draft.reference shouldBe "ref-1"
                draft.content shouldBe case.content
            }
        }
    })
