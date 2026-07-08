package no.nav.budstikka.domain.beslutning

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.formidling.AltinnRessurs
import no.nav.budstikka.domain.formidling.AltinnRessursId
import no.nav.budstikka.domain.formidling.ArbeidsgivervarselOpprett
import no.nav.budstikka.domain.formidling.BrukervarselOpprett
import no.nav.budstikka.domain.formidling.Formidling
import no.nav.budstikka.domain.formidling.Formidlingsinnhold
import no.nav.budstikka.domain.formidling.LedervarselOpprett
import no.nav.budstikka.domain.formidling.Merkelapp
import no.nav.budstikka.domain.formidling.Orgnummer
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.Varseltype
import no.nav.budstikka.infrastructure.pdl.FakeDodsfall
import java.util.UUID

class GrunnlagsinnhenterTest :
    FunSpec({
        val sykmeldt = Personident("11111111111")
        val orgnr = Orgnummer("987654321")

        fun envelope(innhold: Formidlingsinnhold) = Formidling(eventId = UUID.randomUUID(), referanse = "ref-1", innhold = innhold)

        test("brukerrettet person som er død gir mottakerErDod=true") {
            val fake = FakeDodsfall().apply { marker(sykmeldt) }
            val grunnlag = Grunnlagsinnhenter(fake).innhent(envelope(BrukervarselOpprett(sykmeldt, Varseltype.BESKJED, "t")))
            grunnlag shouldBe Beslutningsgrunnlag(mottakerErDod = true)
        }

        test("brukerrettet person som lever gir mottakerErDod=false") {
            val grunnlag = Grunnlagsinnhenter(FakeDodsfall()).innhent(envelope(BrukervarselOpprett(sykmeldt, Varseltype.BESKJED, "t")))
            grunnlag shouldBe Beslutningsgrunnlag(mottakerErDod = false)
        }

        test("ledervarsel slår ikke opp død på den sykmeldte (ingen gate) selv om markert død") {
            val fake = FakeDodsfall().apply { marker(sykmeldt) }
            val grunnlag = Grunnlagsinnhenter(fake).innhent(envelope(LedervarselOpprett(sykmeldt, orgnr, "t")))
            grunnlag shouldBe Beslutningsgrunnlag(mottakerErDod = false)
        }

        test("arbeidsgivervarsel (Altinn) har ingen person å slå opp") {
            val ag =
                ArbeidsgivervarselOpprett(
                    orgnummer = orgnr,
                    mottaker = AltinnRessurs(AltinnRessursId.DIALOGMOETE),
                    merkelapp = Merkelapp.OPPFOELGING,
                    tekst = "t",
                    lenke = "https://nav.no",
                )
            val grunnlag = Grunnlagsinnhenter(FakeDodsfall().apply { marker(sykmeldt) }).innhent(envelope(ag))
            grunnlag shouldBe Beslutningsgrunnlag(mottakerErDod = false)
        }
    })
