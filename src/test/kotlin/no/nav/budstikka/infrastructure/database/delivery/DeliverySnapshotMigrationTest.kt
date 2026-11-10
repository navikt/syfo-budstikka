package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.delivery.ArbeidsgiversakConfig
import no.nav.budstikka.application.delivery.Arbeidsgiversaker
import no.nav.budstikka.application.delivery.ArbeidsgivervarselChannelHandler
import no.nav.budstikka.application.delivery.DeliveryOutcome
import no.nav.budstikka.application.delivery.LegacyDeliverySnapshotResolver
import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.delivery.NoDeliveryMetrics
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.NarmesteLederRelasjon
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliverySnapshot
import no.nav.budstikka.fakes.FakeArbeidsgiverNotificationPublisher
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakePersonNameLookup
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.time.Duration.Companion.minutes

class DeliverySnapshotMigrationTest :
    FunSpec({
        test("V11 upgrade preserves old writers and every existing state; resolved leader rows enforce snapshot data") {
            PostgresTestFixture().use { fixture ->
                fixture.migrate("11")
                DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
                    connection.createStatement().use { sql ->
                        val payload = """
                            '{"type":"ArbeidsgivervarselCreate","orgnummer":"999999999",
                              "tag":"tag","text":"text","link":"https://nav.no/path",
                              "mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",
                                "externalVarsling":{"emailTitle":"title","emailText":"body"}}}'::jsonb
                        """

                        fun insert(state: String) =
                            """
                        INSERT INTO delivery(reference, operation, channel, recipient_type, recipient_id,
                                             payload, state, claim_token, next_attempt_time)
                        VALUES ('upgrade-$state', 'CREATE', 'ARBEIDSGIVERVARSEL', 'VIRKSOMHET', '999999999', $payload, '$state',
                        ${if (state == "CLAIMED") "gen_random_uuid()" else "NULL"}, ${if (state == "CLAIMED") "now()" else "NULL"})
                    """
                        listOf("READY", "CLAIMED", "SENT", "FAILED").forEach { sql.executeUpdate(insert(it)) }
                        fixture.migrate()
                        sql.executeQuery("SELECT count(*) FROM delivery WHERE snapshot_state = 'LEGACY'").use {
                            it.next()
                            it.getInt(1) shouldBe 4
                        }
                        sql.executeUpdate(insert("READY"))
                        val repository = PostgresDeliveryRepository(fixture.database)
                        val pending = repository.claim(10, 1.minutes, 10, setOf(Channel.ARBEIDSGIVERVARSEL))
                        pending.size shouldBe 3
                        val publisher = FakeArbeidsgiverNotificationPublisher()
                        val legacy =
                            LegacyDeliverySnapshotResolver(
                                NarmesteLederLookup { _, _ ->
                                    NarmesteLederRelasjon(
                                        PersonIdentifier("2".repeat(11)),
                                        listOf("snapshot@example.test"),
                                        TEST_LEDERRELASJON_ID,
                                    )
                                },
                                FakePersonNameLookup(),
                                NoDeliveryMetrics,
                            )
                        val handler =
                            ArbeidsgivervarselChannelHandler(
                                publisher,
                                Arbeidsgiversaker(FakeArbeidsgiversakPublisher(), ArbeidsgiversakConfig("https://nav.no/path")),
                                legacy,
                            )
                        for (delivery in pending) {
                            delivery.snapshot shouldBe DeliverySnapshot.Legacy
                            repository.beginAttempt(delivery.claim, 10) shouldBe true
                            handler.handle(delivery) shouldBe DeliveryOutcome.Sent
                            repository.markSent(delivery.claim) shouldBe true
                        }
                        publisher.requests.size shouldBe 3
                        sql.executeUpdate(
                            "UPDATE delivery SET state = 'CLAIMED', claim_token = gen_random_uuid(), next_attempt_time = now() WHERE state = 'READY'",
                        )
                        sql.executeUpdate(
                            "UPDATE delivery SET state = 'SENT', claim_token = NULL, next_attempt_time = NULL WHERE state = 'CLAIMED'",
                        )
                        sql.executeQuery("SELECT count(*) FROM delivery").use {
                            it.next()
                            it.getInt(1) shouldBe 5
                        }
                        shouldThrow<SQLException> { sql.executeUpdate("UPDATE delivery SET snapshot_state = 'RESOLVED'") }.sqlState shouldBe
                            "23514"
                        sql.executeUpdate(
                            """
                        UPDATE delivery SET snapshot_state = 'RESOLVED', leader_fnr = '${"2".repeat(11)}',
                        leader_emails = '["snapshot@example.test"]'::jsonb, leader_relation_id = '00000000-0000-0000-0000-000000000022'
                    """,
                        )
                        sql.executeQuery("SELECT count(*) FROM delivery WHERE snapshot_state = 'RESOLVED' AND name_first IS NULL").use {
                            it.next()
                            it.getInt(1) shouldBe 5
                        }
                        shouldThrow<SQLException> { sql.executeUpdate("UPDATE delivery SET leader_emails = '[]'::jsonb") }.sqlState shouldBe
                            "23514"
                        shouldThrow<SQLException> { sql.executeUpdate("UPDATE delivery SET leader_relation_id = NULL") }.sqlState shouldBe
                            "23514"
                        shouldThrow<SQLException> { sql.executeUpdate("UPDATE delivery SET name_first = 'Synthetic'") }.sqlState shouldBe
                            "23514"
                        sql.executeUpdate(
                            "UPDATE delivery SET payload = payload #- '{mottaker,externalVarsling}', leader_emails = '[]'::jsonb",
                        )
                        sql.executeUpdate(
                            """
                        INSERT INTO delivery(reference, operation, channel, recipient_type, recipient_id, payload, snapshot_state)
                        VALUES ('other-channel', 'CREATE', 'MICROFRONTEND', 'PERSON', '00000000000', '{}'::jsonb, 'RESOLVED')
                    """,
                        )
                    }
                }
            }
        }
    })
