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
import no.nav.budstikka.domain.dispatch.Tag
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT

class DispatchDraftMappingTest :
    FunSpec({
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
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text"),
                    Channel.BRUKERVARSEL,
                    Operation.CREATE,
                    Recipient.Person(TEST_SYKMELDT),
                ),
                Case(
                    "BrukervarselInactivate",
                    BrukervarselInactivate("ref-1", TEST_SYKMELDT),
                    Channel.BRUKERVARSEL,
                    Operation.INACTIVATE,
                    Recipient.Person(TEST_SYKMELDT),
                ),
                Case(
                    "DittSykefravaer",
                    DittSykefravaerCreate(TEST_SYKMELDT, "text"),
                    Channel.DITT_SYKEFRAVAER,
                    Operation.CREATE,
                    Recipient.Person(TEST_SYKMELDT),
                ),
                Case("Brev", BrevCreate(TEST_SYKMELDT, "jp-1"), Channel.BREV, Operation.CREATE, Recipient.Person(TEST_SYKMELDT)),
                Case(
                    "MicrofrontendEnable",
                    MicrofrontendEnable(TEST_SYKMELDT, "mf-1"),
                    Channel.MICROFRONTEND,
                    Operation.CREATE,
                    Recipient.Person(TEST_SYKMELDT),
                ),
                Case(
                    "MicrofrontendDisable",
                    MicrofrontendDisable(TEST_SYKMELDT, "mf-1"),
                    Channel.MICROFRONTEND,
                    Operation.INACTIVATE,
                    Recipient.Person(TEST_SYKMELDT),
                ),
                Case(
                    "Ledervarsel",
                    LedervarselCreate(TEST_SYKMELDT, TEST_ORGNUMMER, "text"),
                    Channel.LEDERVARSEL,
                    Operation.CREATE,
                    Recipient.Person(TEST_SYKMELDT),
                ),
                Case(
                    "Arbeidsgivervarsel",
                    ArbeidsgivervarselCreate(
                        orgnummer = TEST_ORGNUMMER,
                        recipient = NarmesteLeder(TEST_SYKMELDT),
                        tag = Tag.DIALOGMOETE,
                        text = "text",
                        link = "https://nav.no",
                    ),
                    Channel.ARBEIDSGIVERVARSEL,
                    Operation.CREATE,
                    Recipient.Virksomhet(TEST_ORGNUMMER),
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
