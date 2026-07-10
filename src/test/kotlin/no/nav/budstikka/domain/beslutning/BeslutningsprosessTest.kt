package no.nav.budstikka.domain.beslutning

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.formidling.BrukervarselCreate
import no.nav.budstikka.domain.formidling.Formidling
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.Varseltype
import no.nav.budstikka.infrastructure.pdl.FakeDeathLookup
import java.util.UUID

class BeslutningsprosessTest :
    FunSpec({
        val sykmeldt = Personident("11111111111")

        fun hendelse() =
            Formidling(
                eventId = UUID.randomUUID(),
                referanse = "ref-1",
                content = BrukervarselCreate(sykmeldt, Varseltype.OPPGAVE, "text"),
            )

        test("død person → Dropped(DEAD) ende-til-ende via død-oppslaget") {
            val fake = FakeDeathLookup().apply { mark(sykmeldt) }
            val prosess = Beslutningsprosess(GrunnlagFetcher(fake))
            prosess.process(hendelse()) shouldBe Beslutning.Dropped(DropReason.DEAD)
        }

        test("levende person → Processed med én leveranse") {
            val prosess = Beslutningsprosess(GrunnlagFetcher(FakeDeathLookup()))
            prosess.process(hendelse()).shouldBeInstanceOf<Beslutning.Processed>()
        }
    })
