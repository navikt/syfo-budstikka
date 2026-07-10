package no.nav.budstikka.domain.beslutning

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.formidling.AltinnRessurs
import no.nav.budstikka.domain.formidling.AltinnRessursId
import no.nav.budstikka.domain.formidling.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.formidling.BrukervarselCreate
import no.nav.budstikka.domain.formidling.Formidling
import no.nav.budstikka.domain.formidling.Formidlingsinnhold
import no.nav.budstikka.domain.formidling.LedervarselCreate
import no.nav.budstikka.domain.formidling.Merkelapp
import no.nav.budstikka.domain.formidling.Orgnummer
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.Varseltype
import no.nav.budstikka.infrastructure.pdl.FakeDeathLookup
import java.util.UUID

class GrunnlagFetcherTest :
    FunSpec({
        val sykmeldt = Personident("11111111111")
        val orgnr = Orgnummer("987654321")

        fun envelope(content: Formidlingsinnhold) = Formidling(eventId = UUID.randomUUID(), referanse = "ref-1", content = content)

        test("brukerrettet person som er død gir mottakerIsDead=true") {
            val fake = FakeDeathLookup().apply { mark(sykmeldt) }
            val grunnlag = GrunnlagFetcher(fake).fetch(envelope(BrukervarselCreate(sykmeldt, Varseltype.BESKJED, "t")))
            grunnlag shouldBe Beslutningsgrunnlag(mottakerIsDead = true)
        }

        test("brukerrettet person som lever gir mottakerIsDead=false") {
            val grunnlag = GrunnlagFetcher(FakeDeathLookup()).fetch(envelope(BrukervarselCreate(sykmeldt, Varseltype.BESKJED, "t")))
            grunnlag shouldBe Beslutningsgrunnlag(mottakerIsDead = false)
        }

        test("ledervarsel slår ikke opp død på den sykmeldte (ingen gate) selv om markert død") {
            val fake = FakeDeathLookup().apply { mark(sykmeldt) }
            val grunnlag = GrunnlagFetcher(fake).fetch(envelope(LedervarselCreate(sykmeldt, orgnr, "t")))
            grunnlag shouldBe Beslutningsgrunnlag(mottakerIsDead = false)
        }

        test("arbeidsgivervarsel (Altinn) har ingen person å slå opp") {
            val ag =
                ArbeidsgivervarselCreate(
                    orgnummer = orgnr,
                    mottaker = AltinnRessurs(AltinnRessursId.DIALOGMOETE),
                    merkelapp = Merkelapp.OPPFOELGING,
                    text = "t",
                    link = "https://nav.no",
                )
            val grunnlag = GrunnlagFetcher(FakeDeathLookup().apply { mark(sykmeldt) }).fetch(envelope(ag))
            grunnlag shouldBe Beslutningsgrunnlag(mottakerIsDead = false)
        }
    })
