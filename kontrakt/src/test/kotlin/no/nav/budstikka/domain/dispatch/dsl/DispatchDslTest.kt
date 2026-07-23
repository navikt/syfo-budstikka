package no.nav.budstikka.domain.dispatch.dsl

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import no.nav.budstikka.domain.dispatch.AltinnResourceId
import no.nav.budstikka.domain.dispatch.ArbeidsgiverMeldingstype
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchHeader
import no.nav.budstikka.domain.dispatch.EksternKanal
import no.nav.budstikka.domain.dispatch.Merkelapp
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.domain.dispatch.dispatchJson
import java.util.UUID

private val SYKMELDT = PersonIdentifier("12345678901")
private val ORGNR = Orgnummer("987654321")

private fun EncodedDispatch.decode(): Dispatch = dispatchJson.decodeFromString<Dispatch>(value)

class DispatchDslTest :
    FunSpec({

        test("brukervarselCreate: required params + nested eksternVarsling + brevFallback") {
            val encoded =
                brukervarselCreate(
                    reference = "sak-1",
                    personIdentifier = SYKMELDT,
                    varseltype = Varseltype.BESKJED,
                    text = "Du har fått et varsel"
                ) {
                    link = "https://nav.no/x"
                    eksternVarsling {
                        kanaler(EksternKanal.SMS)
                        smsTekst = "Sjekk Min side"
                    }
                    brevFallback(journalpostId = "jp-1")
                }

            encoded.partitionKey shouldBe SYKMELDT.value
            encoded.headerName shouldBe DispatchHeader.EVENT_ID

            val dispatch = encoded.decode()
            dispatch.reference shouldBe "sak-1"
            val content = dispatch.content as BrukervarselCreate
            content.varseltype shouldBe Varseltype.BESKJED
            content.text shouldBe "Du har fått et varsel"
            content.link shouldBe "https://nav.no/x"
            content.eksternVarsling?.kanaler shouldBe setOf(EksternKanal.SMS)
            content.eksternVarsling?.smsTekst shouldBe "Sjekk Min side"
            content.brevFallback?.journalpostId shouldBe "jp-1"
        }

        test("eksternVarsling uten kanaler bruker bibliotekets default (SMS + e-post)") {
            val encoded =
                brukervarselCreate("r", SYKMELDT, Varseltype.OPPGAVE, "t") {
                    eksternVarsling { smsTekst = "x" }
                }
            val content = encoded.decode().content as BrukervarselCreate
            content.eksternVarsling?.kanaler shouldBe setOf(EksternKanal.SMS, EksternKanal.EMAIL)
        }

        test("arbeidsgivervarselCreate: sealed recipient via narmesteLeder-helper + serialiserer riktig type") {
            val encoded =
                arbeidsgivervarselCreate(
                    reference = "sak-2",
                    orgnummer = ORGNR,
                    recipient = narmesteLeder(SYKMELDT),
                    merkelapp = Merkelapp.DIALOGMOETE,
                    text = "Dialogmøte",
                    link = "https://nav.no/ag",
                ) {
                    meldingstype = ArbeidsgiverMeldingstype.OPPGAVE
                    sakstilknytning(sakId = "sak-2")
                }

            encoded.partitionKey shouldBe ORGNR.value
            encoded.value shouldContain "\"type\":\"ArbeidsgivervarselCreate\""

            val content = encoded.decode().content as ArbeidsgivervarselCreate
            content.merkelapp shouldBe Merkelapp.DIALOGMOETE
            content.meldingstype shouldBe ArbeidsgiverMeldingstype.OPPGAVE
            content.sakstilknytning?.sakId shouldBe "sak-2"
        }

        test("altinnRessurs-helper gir Altinn-mottaker") {
            val encoded =
                arbeidsgivervarselCreate(
                    "r",
                    ORGNR,
                    recipient = altinnRessurs(AltinnResourceId.DIALOGMOETE),
                    merkelapp = Merkelapp.OPPFOELGING,
                    text = "t",
                    link = "l",
                )
            encoded.value shouldContain "\"type\":\"AltinnRessurs\""
        }

        test("brevCreate: partisjonsnøkkel = person, default distribution IMPORTANT") {
            val encoded = brevCreate("r", SYKMELDT, journalpostId = "jp-9")
            encoded.partitionKey shouldBe SYKMELDT.value
            (encoded.decode().content as BrevCreate).distributionType shouldBe
                no.nav.budstikka.domain.dispatch.DistributionType.IMPORTANT
        }

        test("inactivate: content.reference == konvolutt-reference, nøkkel = sykmeldt") {
            val encoded = brukervarselInactivate(reference = "ref-7", sykmeldt = SYKMELDT)
            encoded.partitionKey shouldBe SYKMELDT.value
            val dispatch = encoded.decode()
            dispatch.reference shouldBe "ref-7"
            (dispatch.content as BrukervarselInactivate).reference shouldBe "ref-7"
        }

        test("eventId er ikke-tom og unik per kall") {
            val a = brevCreate("r", SYKMELDT, "jp")
            val b = brevCreate("r", SYKMELDT, "jp")
            a.eventId.shouldNotBeEmpty()
            (a.eventId == b.eventId) shouldBe false
        }

        test("alle 11 variantene bygger og round-tripper") {
            val all =
                listOf(
                    brukervarselCreate("r", SYKMELDT, Varseltype.BESKJED, "t"),
                    ledervarselCreate("r", SYKMELDT, ORGNR, "t"),
                    dittSykefravaerCreate("r", SYKMELDT, "t"),
                    arbeidsgivervarselCreate("r", ORGNR, narmesteLeder(SYKMELDT), Merkelapp.DIALOGMOETE, "t", "l"),
                    brevCreate("r", SYKMELDT, "jp"),
                    brukervarselInactivate("r", SYKMELDT),
                    ledervarselInactivate("r", SYKMELDT),
                    dittSykefravaerInactivate("r", SYKMELDT),
                    arbeidsgivervarselInactivate("r", ORGNR),
                    microfrontendEnable("r", SYKMELDT, "mf-1"),
                    microfrontendDisable("r", SYKMELDT, "mf-1"),
                )
            all shouldHaveSize 11
            all.forEach { it.decode().content.partitionKey shouldBe it.partitionKey }
        }
    })
