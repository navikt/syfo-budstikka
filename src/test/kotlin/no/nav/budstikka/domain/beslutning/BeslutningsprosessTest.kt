package no.nav.budstikka.domain.beslutning

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.formidling.BrukervarselOpprett
import no.nav.budstikka.domain.formidling.Formidling
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.Varseltype
import no.nav.budstikka.infrastructure.pdl.FakeDodsfall
import java.util.UUID

class BeslutningsprosessTest :
    FunSpec({
        val sykmeldt = Personident("11111111111")

        fun hendelse() =
            Formidling(
                eventId = UUID.randomUUID(),
                referanse = "ref-1",
                innhold = BrukervarselOpprett(sykmeldt, Varseltype.OPPGAVE, "tekst"),
            )

        test("død person → Droppet(DOD) ende-til-ende via død-oppslaget") {
            val fake = FakeDodsfall().apply { marker(sykmeldt) }
            val prosess = Beslutningsprosess(Grunnlagsinnhenter(fake))
            prosess.behandle(hendelse()) shouldBe Beslutning.Droppet(DropAarsak.DOD)
        }

        test("levende person → Behandlet med én leveranse") {
            val prosess = Beslutningsprosess(Grunnlagsinnhenter(FakeDodsfall()))
            prosess.behandle(hendelse()).shouldBeInstanceOf<Beslutning.Behandlet>()
        }
    })
