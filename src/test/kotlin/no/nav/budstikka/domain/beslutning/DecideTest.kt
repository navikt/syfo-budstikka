package no.nav.budstikka.domain.beslutning

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.formidling.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.formidling.BrevCreate
import no.nav.budstikka.domain.formidling.BrukervarselCreate
import no.nav.budstikka.domain.formidling.BrukervarselInactivate
import no.nav.budstikka.domain.formidling.DittSykefravaerCreate
import no.nav.budstikka.domain.formidling.Formidling
import no.nav.budstikka.domain.formidling.Formidlingsinnhold
import no.nav.budstikka.domain.formidling.LedervarselCreate
import no.nav.budstikka.domain.formidling.Merkelapp
import no.nav.budstikka.domain.formidling.MikrofrontendDisable
import no.nav.budstikka.domain.formidling.MikrofrontendEnable
import no.nav.budstikka.domain.formidling.NarmesteLeder
import no.nav.budstikka.domain.formidling.Orgnummer
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.Varseltype
import java.util.UUID

class DecideTest :
    FunSpec({
        val sykmeldt = Personident("11111111111")
        val orgnr = Orgnummer("987654321")

        fun envelope(content: Formidlingsinnhold) = Formidling(eventId = UUID.randomUUID(), referanse = "ref-1", content = content)

        val brukervarsel = BrukervarselCreate(sykmeldt, Varseltype.OPPGAVE, "text")

        context("død-gate") {
            val gatedCreates =
                listOf(
                    "Brukervarsel" to brukervarsel,
                    "DittSykefravaer" to DittSykefravaerCreate(sykmeldt, "text"),
                    "Brev" to BrevCreate(sykmeldt, "jp-1"),
                    "MikrofrontendEnable" to MikrofrontendEnable(sykmeldt, "mf-1"),
                )

            gatedCreates.forEach { (name, content) ->
                test("brukerrettet CREATE ($name) til død person droppes med DEAD") {
                    val beslutning = decide(envelope(content), Beslutningsgrunnlag(mottakerIsDead = true))
                    beslutning shouldBe Beslutning.Dropped(DropReason.DEAD)
                }
            }

            test("levende person gir Processed") {
                val beslutning = decide(envelope(brukervarsel), Beslutningsgrunnlag(mottakerIsDead = false))
                beslutning.shouldBeInstanceOf<Beslutning.Processed>().leveranser shouldHaveSize 1
            }

            test("lukkeoperasjon (INACTIVATE) gates ikke selv om mottaker er død") {
                val inactivate = BrukervarselInactivate(referanse = "ref-1", sykmeldt = sykmeldt)
                decide(envelope(inactivate), Beslutningsgrunnlag(mottakerIsDead = true))
                    .shouldBeInstanceOf<Beslutning.Processed>()
            }

            test("mikrofrontend-deaktivering gates ikke selv om mottaker er død") {
                val disable = MikrofrontendDisable(personident = sykmeldt, mikrofrontendId = "mf-1")
                decide(envelope(disable), Beslutningsgrunnlag(mottakerIsDead = true))
                    .shouldBeInstanceOf<Beslutning.Processed>()
            }

            test("ledervarsel gates ikke på den sykmeldtes død (mottaker er lederen)") {
                val ledervarsel = LedervarselCreate(sykmeldt = sykmeldt, orgnummer = orgnr, text = "text")
                decide(envelope(ledervarsel), Beslutningsgrunnlag(mottakerIsDead = true))
                    .shouldBeInstanceOf<Beslutning.Processed>()
            }
        }

        context("ruting til kanal/operation/mottaker") {
            data class Case(
                val name: String,
                val content: Formidlingsinnhold,
                val kanal: Kanal,
                val operation: Operation,
                val mottaker: Mottaker,
            )

            val cases =
                listOf(
                    Case("Brukervarsel", brukervarsel, Kanal.BRUKERVARSEL, Operation.CREATE, Mottaker.Person(sykmeldt)),
                    Case(
                        "DittSykefravaer",
                        DittSykefravaerCreate(sykmeldt, "text"),
                        Kanal.DITT_SYKEFRAVAER,
                        Operation.CREATE,
                        Mottaker.Person(sykmeldt),
                    ),
                    Case(
                        "Brev",
                        BrevCreate(sykmeldt, "jp-1"),
                        Kanal.BREV,
                        Operation.CREATE,
                        Mottaker.Person(sykmeldt),
                    ),
                    Case(
                        "MikrofrontendEnable",
                        MikrofrontendEnable(sykmeldt, "mf-1"),
                        Kanal.MIKROFRONTEND,
                        Operation.CREATE,
                        Mottaker.Person(sykmeldt),
                    ),
                    Case(
                        "Ledervarsel",
                        LedervarselCreate(sykmeldt, orgnr, "text"),
                        Kanal.LEDERVARSEL,
                        Operation.CREATE,
                        Mottaker.Person(sykmeldt),
                    ),
                    Case(
                        "Arbeidsgivervarsel",
                        ArbeidsgivervarselCreate(
                            orgnummer = orgnr,
                            mottaker = NarmesteLeder(sykmeldt),
                            merkelapp = Merkelapp.DIALOGMOETE,
                            text = "text",
                            link = "https://nav.no",
                        ),
                        Kanal.ARBEIDSGIVERVARSEL,
                        Operation.CREATE,
                        Mottaker.Virksomhet(orgnr),
                    ),
                    Case(
                        "BrukervarselInactivate",
                        BrukervarselInactivate("ref-1", sykmeldt),
                        Kanal.BRUKERVARSEL,
                        Operation.INACTIVATE,
                        Mottaker.Person(sykmeldt),
                    ),
                )

            cases.forEach { case ->
                test("${case.name} → ${case.kanal}/${case.operation}") {
                    val leveranse =
                        decide(envelope(case.content), Beslutningsgrunnlag())
                            .shouldBeInstanceOf<Beslutning.Processed>()
                            .leveranser
                            .single()
                    leveranse.kanal shouldBe case.kanal
                    leveranse.operation shouldBe case.operation
                    leveranse.mottaker shouldBe case.mottaker
                    leveranse.referanse shouldBe "ref-1"
                    leveranse.content shouldBe case.content
                }
            }
        }
    })
