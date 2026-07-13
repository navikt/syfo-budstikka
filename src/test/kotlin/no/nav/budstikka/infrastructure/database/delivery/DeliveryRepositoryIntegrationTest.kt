package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepositoryImpl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class DeliveryRepositoryIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = 5.minutes

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        suspend fun saveDraft(
            reference: String,
            draft: DeliveryDraft,
        ) {
            val inboxEventId = UUID.randomUUID()
            InboxMessageRepositoryImpl(fixture.database).saveBatch(listOf(inboxEventId to """{"eventId":"$inboxEventId"}"""))
            fixture.database.transact {
                DeliveryRepositoryImpl(fixture.database).saveInTransaction(inboxEventId, listOf(draft.copy(reference = reference)))
            }
        }

        suspend fun rowForReference(reference: String) =
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.reference eq reference }.single()
            }

        suspend fun expireLease(deliveryId: UUID) {
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq deliveryId }) {
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }
        }

        suspend fun makePoison(
            deliveryId: UUID,
            attempt: Int,
        ) {
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq deliveryId }) {
                    it[state] = DeliveryState.CLAIMED.name
                    it[DeliveryTable.attempt] = attempt
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }
        }

        test("claim picks only requested channels and marks rows CLAIMED") {
            val repository = DeliveryRepositoryImpl(fixture.database)
            saveDraft("micro-ref", microfrontendDraft())
            saveDraft("bruker-ref", brukervarselDraft())

            val claimed = repository.claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))

            claimed.shouldHaveSize(1)
            claimed.single().channel shouldBe Channel.MICROFRONTEND
            rowForReference("micro-ref")[DeliveryTable.state] shouldBe "CLAIMED"
            rowForReference("micro-ref")[DeliveryTable.attempt] shouldBe 1
            rowForReference("micro-ref")[DeliveryTable.nextAttemptTime] shouldNotBe null
            rowForReference("bruker-ref")[DeliveryTable.state] shouldBe "READY"
            rowForReference("bruker-ref")[DeliveryTable.attempt] shouldBe 0
        }

        test("claim reclaims a CLAIMED row after lease expiry") {
            val repository = DeliveryRepositoryImpl(fixture.database)
            saveDraft("micro-ref", microfrontendDraft())

            val initialClaim = repository.claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))
            initialClaim.shouldHaveSize(1)
            val deliveryId = initialClaim.single().id
            expireLease(deliveryId)

            val reclaimed = repository.claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))

            reclaimed.shouldHaveSize(1)
            reclaimed.single().id shouldBe deliveryId
            rowForReference("micro-ref")[DeliveryTable.attempt] shouldBe 2
        }

        test("markSent transitions a CLAIMED row to SENT") {
            val repository = DeliveryRepositoryImpl(fixture.database)
            saveDraft("micro-ref", microfrontendDraft())
            val deliveryId =
                repository
                    .claim(
                        limit = 10,
                        lease = lease,
                        maxAttempts = 10,
                        channels = setOf(Channel.MICROFRONTEND),
                    ).single()
                    .id

            repository.markSent(deliveryId) shouldBe true

            val row = rowForReference("micro-ref")
            row[DeliveryTable.state] shouldBe "SENT"
            row[DeliveryTable.nextAttemptTime] shouldBe null
            row[DeliveryTable.errorMessage] shouldBe null
        }

        test("markFailed transitions a CLAIMED row to FAILED with reason") {
            val repository = DeliveryRepositoryImpl(fixture.database)
            saveDraft("micro-ref", microfrontendDraft())
            val deliveryId =
                repository
                    .claim(
                        limit = 10,
                        lease = lease,
                        maxAttempts = 10,
                        channels = setOf(Channel.MICROFRONTEND),
                    ).single()
                    .id
            val reason = "Invalid microfrontend payload"

            repository.markFailed(deliveryId, reason) shouldBe true

            val row = rowForReference("micro-ref")
            row[DeliveryTable.state] shouldBe "FAILED"
            row[DeliveryTable.nextAttemptTime] shouldBe null
            row[DeliveryTable.errorMessage] shouldBe reason
        }

        test("claim fails a poison delivery that reached maxAttempts instead of reclaiming it") {
            val repository = DeliveryRepositoryImpl(fixture.database)
            saveDraft("poison-ref", microfrontendDraft())
            val maxAttempts = 3
            val channels = setOf(Channel.MICROFRONTEND)

            repeat(maxAttempts) {
                val claimed = repository.claim(limit = 10, lease = lease, maxAttempts = maxAttempts, channels = channels)
                claimed.shouldHaveSize(1)
                expireLease(claimed.single().id)
            }

            repository.claim(limit = 10, lease = lease, maxAttempts = maxAttempts, channels = channels).shouldHaveSize(0)

            val row = rowForReference("poison-ref")
            row[DeliveryTable.state] shouldBe "FAILED"
            row[DeliveryTable.attempt] shouldBe maxAttempts
            row[DeliveryTable.nextAttemptTime] shouldBe null
            row[DeliveryTable.errorMessage] shouldNotBe null
        }

        test("a poison delivery does not block a healthy newer delivery on the same channel") {
            val repository = DeliveryRepositoryImpl(fixture.database)
            saveDraft("poison-ref", microfrontendDraft())
            saveDraft("healthy-ref", microfrontendDraft())
            val poisonId = rowForReference("poison-ref")[DeliveryTable.id]
            makePoison(poisonId, attempt = 3)

            val claimed = repository.claim(limit = 1, lease = lease, maxAttempts = 3, channels = setOf(Channel.MICROFRONTEND))

            claimed.map { it.id } shouldBe listOf(rowForReference("healthy-ref")[DeliveryTable.id])
            rowForReference("poison-ref")[DeliveryTable.state] shouldBe "FAILED"
        }
    })

private fun microfrontendDraft(): DeliveryDraft {
    val ident = PersonIdentifier("12345678901")
    return DeliveryDraft(
        reference = "unused",
        operation = Operation.CREATE,
        channel = Channel.MICROFRONTEND,
        recipient = Recipient.Person(ident),
        content = MicrofrontendEnable(personIdentifier = ident, mikrofrontendId = "syfo-mikrofrontend"),
    )
}

private fun brukervarselDraft(): DeliveryDraft {
    val ident = PersonIdentifier("12345678901")
    return DeliveryDraft(
        reference = "unused",
        operation = Operation.CREATE,
        channel = Channel.BRUKERVARSEL,
        recipient = Recipient.Person(ident),
        content = BrukervarselCreate(personIdentifier = ident, varseltype = Varseltype.BESKJED, text = "Hei"),
    )
}
