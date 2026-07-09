package no.nav.budstikka.domain.beslutning

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.formidling.ArbeidsgivervarselOpprett
import no.nav.budstikka.domain.formidling.BrevOpprett
import no.nav.budstikka.domain.formidling.BrukervarselInaktiver
import no.nav.budstikka.domain.formidling.BrukervarselOpprett
import no.nav.budstikka.domain.formidling.DittSykefravaerOpprett
import no.nav.budstikka.domain.formidling.Formidling
import no.nav.budstikka.domain.formidling.Formidlingsinnhold
import no.nav.budstikka.domain.formidling.LedervarselOpprett
import no.nav.budstikka.domain.formidling.Merkelapp
import no.nav.budstikka.domain.formidling.MikrofrontendAktiver
import no.nav.budstikka.domain.formidling.MikrofrontendDeaktiver
import no.nav.budstikka.domain.formidling.NarmesteLeder
import no.nav.budstikka.domain.formidling.Orgnummer
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.Varseltype
import java.util.UUID

class DecideTest :
    FunSpec({
        val sykmeldt = Personident("11111111111")
        val orgnr = Orgnummer("987654321")

        fun envelope(innhold: Formidlingsinnhold) = Formidling(eventId = UUID.randomUUID(), referanse = "ref-1", innhold = innhold)

        val brukervarsel = BrukervarselOpprett(sykmeldt, Varseltype.OPPGAVE, "tekst")

        context("død-gate") {
            val gatedeOpprett =
                listOf(
                    "Brukervarsel" to brukervarsel,
                    "DittSykefravaer" to DittSykefravaerOpprett(sykmeldt, "tekst"),
                    "Brev" to BrevOpprett(sykmeldt, "jp-1"),
                    "MikrofrontendAktiver" to MikrofrontendAktiver(sykmeldt, "mf-1"),
                )

            gatedeOpprett.forEach { (navn, innhold) ->
                test("brukerrettet OPPRETT ($navn) til død person droppes med DOD") {
                    val beslutning = decide(envelope(innhold), Beslutningsgrunnlag(mottakerErDod = true))
                    beslutning shouldBe Beslutning.Droppet(DropAarsak.DOD)
                }
            }

            test("levende person gir Behandlet") {
                val beslutning = decide(envelope(brukervarsel), Beslutningsgrunnlag(mottakerErDod = false))
                beslutning.shouldBeInstanceOf<Beslutning.Behandlet>().leveranser shouldHaveSize 1
            }

            test("lukkeoperasjon (INAKTIVER) gates ikke selv om mottaker er død") {
                val inaktiver = BrukervarselInaktiver(referanse = "ref-1", sykmeldt = sykmeldt)
                decide(envelope(inaktiver), Beslutningsgrunnlag(mottakerErDod = true))
                    .shouldBeInstanceOf<Beslutning.Behandlet>()
            }

            test("mikrofrontend-deaktivering gates ikke selv om mottaker er død") {
                val deaktiver = MikrofrontendDeaktiver(personident = sykmeldt, mikrofrontendId = "mf-1")
                decide(envelope(deaktiver), Beslutningsgrunnlag(mottakerErDod = true))
                    .shouldBeInstanceOf<Beslutning.Behandlet>()
            }

            test("ledervarsel gates ikke på den sykmeldtes død (mottaker er lederen)") {
                val ledervarsel = LedervarselOpprett(sykmeldt = sykmeldt, orgnummer = orgnr, tekst = "tekst")
                decide(envelope(ledervarsel), Beslutningsgrunnlag(mottakerErDod = true))
                    .shouldBeInstanceOf<Beslutning.Behandlet>()
            }
        }

        context("ruting til kanal/operasjon/mottaker") {
            data class Case(
                val navn: String,
                val innhold: Formidlingsinnhold,
                val kanal: Kanal,
                val operasjon: Operasjon,
                val mottaker: Mottaker,
            )

            val cases =
                listOf(
                    Case("Brukervarsel", brukervarsel, Kanal.BRUKERVARSEL, Operasjon.OPPRETT, Mottaker.Person(sykmeldt)),
                    Case(
                        "DittSykefravaer",
                        DittSykefravaerOpprett(sykmeldt, "tekst"),
                        Kanal.DITT_SYKEFRAVAER,
                        Operasjon.OPPRETT,
                        Mottaker.Person(sykmeldt),
                    ),
                    Case(
                        "Brev",
                        BrevOpprett(sykmeldt, "jp-1"),
                        Kanal.BREV,
                        Operasjon.OPPRETT,
                        Mottaker.Person(sykmeldt),
                    ),
                    Case(
                        "MikrofrontendAktiver",
                        MikrofrontendAktiver(sykmeldt, "mf-1"),
                        Kanal.MIKROFRONTEND,
                        Operasjon.OPPRETT,
                        Mottaker.Person(sykmeldt),
                    ),
                    Case(
                        "Ledervarsel",
                        LedervarselOpprett(sykmeldt, orgnr, "tekst"),
                        Kanal.LEDERVARSEL,
                        Operasjon.OPPRETT,
                        Mottaker.Person(sykmeldt),
                    ),
                    Case(
                        "Arbeidsgivervarsel",
                        ArbeidsgivervarselOpprett(
                            orgnummer = orgnr,
                            mottaker = NarmesteLeder(sykmeldt),
                            merkelapp = Merkelapp.DIALOGMOETE,
                            tekst = "tekst",
                            lenke = "https://nav.no",
                        ),
                        Kanal.ARBEIDSGIVERVARSEL,
                        Operasjon.OPPRETT,
                        Mottaker.Virksomhet(orgnr),
                    ),
                    Case(
                        "BrukervarselInaktiver",
                        BrukervarselInaktiver("ref-1", sykmeldt),
                        Kanal.BRUKERVARSEL,
                        Operasjon.INAKTIVER,
                        Mottaker.Person(sykmeldt),
                    ),
                )

            cases.forEach { case ->
                test("${case.navn} → ${case.kanal}/${case.operasjon}") {
                    val leveranse =
                        decide(envelope(case.innhold), Beslutningsgrunnlag())
                            .shouldBeInstanceOf<Beslutning.Behandlet>()
                            .leveranser
                            .single()
                    leveranse.kanal shouldBe case.kanal
                    leveranse.operasjon shouldBe case.operasjon
                    leveranse.mottaker shouldBe case.mottaker
                    leveranse.referanse shouldBe "ref-1"
                    leveranse.innhold shouldBe case.innhold
                }
            }
        }
    })
