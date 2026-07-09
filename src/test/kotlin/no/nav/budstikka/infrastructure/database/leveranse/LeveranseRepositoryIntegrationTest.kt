package no.nav.budstikka.infrastructure.database.leveranse

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.beslutning.Kanal
import no.nav.budstikka.domain.beslutning.LeveranseUtkast
import no.nav.budstikka.domain.beslutning.Mottaker
import no.nav.budstikka.domain.beslutning.Operasjon
import no.nav.budstikka.domain.formidling.ArbeidsgivervarselOpprett
import no.nav.budstikka.domain.formidling.BrukervarselOpprett
import no.nav.budstikka.domain.formidling.Formidlingsinnhold
import no.nav.budstikka.domain.formidling.Merkelapp
import no.nav.budstikka.domain.formidling.NarmesteLeder
import no.nav.budstikka.domain.formidling.Orgnummer
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.formidling.Varseltype
import no.nav.budstikka.domain.formidling.formidlingJson
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.formidling.InboxFormidlingRepositoryImpl
import java.sql.ResultSet
import java.util.UUID

class LeveranseRepositoryIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        val sykmeldt = Personident("11111111111")
        val orgnr = Orgnummer("987654321")

        suspend fun nyInboxHendelse(): UUID {
            val eventId = UUID.randomUUID()
            InboxFormidlingRepositoryImpl(fixture.database).save(eventId, "{}")
            return eventId
        }

        test("skriver en Person-leveranse med frosne attributter og DB-defaults") {
            val eventId = nyInboxHendelse()
            val innhold = BrukervarselOpprett(sykmeldt, Varseltype.OPPGAVE, "tekst")
            val utkast = LeveranseUtkast("ref-1", Operasjon.OPPRETT, Kanal.BRUKERVARSEL, Mottaker.Person(sykmeldt), innhold)

            LeveranseRepositoryImpl(fixture.database).lagre(eventId, listOf(utkast))

            fixture.singleRow("SELECT * FROM leveranse") {
                getString("referanse") shouldBe "ref-1"
                getString("operasjon") shouldBe "OPPRETT"
                getString("kanal") shouldBe "BRUKERVARSEL"
                getString("mottaker_type") shouldBe "PERSON"
                getString("mottaker_id") shouldBe "11111111111"
                getString("state") shouldBe "KLAR"
                getInt("attempt") shouldBe 0
                getObject("inbox_event_id") shouldBe eventId
                check(getObject("id") != null)
                check(getObject("created_at") != null)
                formidlingJson.decodeFromString(Formidlingsinnhold.serializer(), getString("payload")) shouldBe innhold
            }
        }

        test("mapper Virksomhet-mottaker til VIRKSOMHET/orgnummer") {
            val eventId = nyInboxHendelse()
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

            fixture.singleRow("SELECT mottaker_type, mottaker_id FROM leveranse") {
                getString("mottaker_type") shouldBe "VIRKSOMHET"
                getString("mottaker_id") shouldBe "987654321"
            }
        }

        test("skriver flere leveranser for samme inbox-hendelse i én batch") {
            val eventId = nyInboxHendelse()
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

            fixture.singleRow("SELECT count(*) AS antall FROM leveranse WHERE inbox_event_id = '$eventId'") {
                getInt("antall") shouldBe 3
            }
        }

        test("leveranse overlever hard-delete av inbox-hendelse (FK ON DELETE SET NULL, B42)") {
            val eventId = nyInboxHendelse()
            val innhold = BrukervarselOpprett(sykmeldt, Varseltype.OPPGAVE, "tekst")
            val utkast = LeveranseUtkast("ref-1", Operasjon.OPPRETT, Kanal.BRUKERVARSEL, Mottaker.Person(sykmeldt), innhold)
            LeveranseRepositoryImpl(fixture.database).lagre(eventId, listOf(utkast))

            fixture.exec("DELETE FROM inbox_formidling WHERE event_id = '$eventId'")

            fixture.singleRow("SELECT inbox_event_id FROM leveranse") {
                getObject("inbox_event_id") shouldBe null
            }
        }

        test("tom liste skriver ingen rader") {
            val eventId = nyInboxHendelse()

            LeveranseRepositoryImpl(fixture.database).lagre(eventId, emptyList())

            fixture.singleRow("SELECT count(*) AS antall FROM leveranse") {
                getInt("antall") shouldBe 0
            }
        }
    })

private fun PostgresTestFixture.singleRow(
    query: String,
    assertion: ResultSet.() -> Unit,
) {
    java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement(query).use { statement ->
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.assertion()
                check(!resultSet.next())
            }
        }
    }
}

private fun PostgresTestFixture.exec(sql: String) {
    java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement(sql).use { it.executeUpdate() }
    }
}
