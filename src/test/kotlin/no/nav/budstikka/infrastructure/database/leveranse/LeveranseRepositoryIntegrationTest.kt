package no.nav.budstikka.infrastructure.database.leveranse

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.beslutning.Kanal
import no.nav.budstikka.domain.beslutning.LeveranseUtkast
import no.nav.budstikka.domain.beslutning.Mottaker
import no.nav.budstikka.domain.beslutning.Operasjon
import no.nav.budstikka.domain.formidling.ArbeidsgivervarselOpprett
import no.nav.budstikka.domain.formidling.BrukervarselOpprett
import no.nav.budstikka.domain.formidling.Merkelapp
import no.nav.budstikka.domain.formidling.NarmesteLeder
import no.nav.budstikka.domain.formidling.Orgnummer
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.Varseltype
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.formidling.InboxFormidlingRepositoryImpl
import no.nav.budstikka.infrastructure.database.rowCount
import no.nav.budstikka.infrastructure.database.singleRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.DriverManager
import java.util.UUID

class LeveranseRepositoryIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        val sykmeldt = Personident("11111111111")
        val orgnr = Orgnummer("987654321")

        suspend fun newInboxEvent(): UUID {
            val eventId = UUID.randomUUID()
            InboxFormidlingRepositoryImpl(fixture.database).save(eventId, "{}")
            return eventId
        }

        test("skriver en Person-leveranse med frosne attributter og DB-defaults") {
            val eventId = newInboxEvent()
            val innhold = BrukervarselOpprett(sykmeldt, Varseltype.OPPGAVE, "tekst")
            val utkast = LeveranseUtkast("ref-1", Operasjon.OPPRETT, Kanal.BRUKERVARSEL, Mottaker.Person(sykmeldt), innhold)

            LeveranseRepositoryImpl(fixture.database).lagre(eventId, listOf(utkast))

            fixture.singleRow(LeveranseTable.selectAll()) {
                LeveranseTable.referanse shouldBe "ref-1"
                LeveranseTable.operasjon shouldBe "OPPRETT"
                LeveranseTable.kanal shouldBe "BRUKERVARSEL"
                LeveranseTable.mottakerType shouldBe "PERSON"
                LeveranseTable.mottakerId shouldBe "11111111111"
                LeveranseTable.state shouldBe "READY"
                LeveranseTable.attempt shouldBe 0
                LeveranseTable.inboxEventId shouldBe eventId
                LeveranseTable.payload shouldBe innhold
            }
        }

        test("mapper Virksomhet-mottaker til VIRKSOMHET/orgnummer") {
            val eventId = newInboxEvent()
            val innhold =
                ArbeidsgivervarselOpprett(
                    orgnummer = orgnr,
                    mottaker = NarmesteLeder(sykmeldt),
                    merkelapp = Merkelapp.DIALOGMOETE,
                    tekst = "tekst",
                    lenke = "https://nav.no",
                )
            val utkast = LeveranseUtkast("ref-2", Operasjon.OPPRETT, Kanal.ARBEIDSGIVERVARSEL, Mottaker.Virksomhet(orgnr), innhold)

            LeveranseRepositoryImpl(fixture.database).lagre(eventId, listOf(utkast))

            fixture.singleRow(LeveranseTable.selectAll()) {
                LeveranseTable.mottakerType shouldBe "VIRKSOMHET"
                LeveranseTable.mottakerId shouldBe "987654321"
            }
        }

        test("skriver flere leveranser for samme inbox-hendelse i én batch") {
            val eventId = newInboxEvent()
            val utkast =
                listOf("ref-a", "ref-b", "ref-c").map {
                    LeveranseUtkast(
                        it,
                        Operasjon.OPPRETT,
                        Kanal.BRUKERVARSEL,
                        Mottaker.Person(sykmeldt),
                        BrukervarselOpprett(sykmeldt, Varseltype.OPPGAVE, "tekst"),
                    )
                }

            LeveranseRepositoryImpl(fixture.database).lagre(eventId, utkast)

            fixture.rowCount(LeveranseTable.selectAll().where { LeveranseTable.inboxEventId eq eventId }) shouldBe 3L
        }

        test("leveranse overlever hard-delete av inbox-hendelse (FK ON DELETE SET NULL, B42)") {
            val eventId = newInboxEvent()
            val innhold = BrukervarselOpprett(sykmeldt, Varseltype.OPPGAVE, "tekst")
            val utkast = LeveranseUtkast("ref-1", Operasjon.OPPRETT, Kanal.BRUKERVARSEL, Mottaker.Person(sykmeldt), innhold)
            LeveranseRepositoryImpl(fixture.database).lagre(eventId, listOf(utkast))

            fixture.exec("DELETE FROM inbox_formidling WHERE event_id = '$eventId'")

            fixture.singleRow(LeveranseTable.selectAll()) {
                LeveranseTable.inboxEventId shouldBe null
            }
        }

        test("tom liste skriver ingen rader") {
            val eventId = newInboxEvent()

            LeveranseRepositoryImpl(fixture.database).lagre(eventId, emptyList())

            fixture.rowCount(LeveranseTable.selectAll()) shouldBe 0L
        }
    })

private fun PostgresTestFixture.exec(sql: String) {
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement(sql).use { it.executeUpdate() }
    }
}
