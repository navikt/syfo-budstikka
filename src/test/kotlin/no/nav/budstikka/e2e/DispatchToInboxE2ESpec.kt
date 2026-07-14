package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchHeader
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.dispatchJson
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import no.nav.budstikka.testsupport.BudstikkaTestApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Tynt ende-til-ende-bevis for harnessen (B53): booter HELE appen mot Testcontainers, produserer
 * en `Dispatch` til budstikka-topicet, og asserter at den ekte konsumenten persisterte en rad i
 * `inbox_message`. Tagget `E2E` → ekskludert fra `./gradlew test`, kjøres via `./gradlew e2eTest`.
 */
@Tags("E2E")
class DispatchToInboxE2ESpec :
    FunSpec({
        test("produced Dispatch is ingested into inbox_message by the real consumer") {
            BudstikkaTestApp.start().use { app ->
                val eventId = UUID.randomUUID()
                val ident = PersonIdentifier("12345678901")
                val dispatch =
                    Dispatch(
                        eventId = eventId,
                        reference = "e2e-ref-1",
                        content = MicrofrontendEnable(ident, "syfo-mikrofrontend"),
                    )

                app.produce(
                    topic = app.budstikkaTopic,
                    key = dispatch.content.partitionKey,
                    value = dispatchJson.encodeToString(dispatch),
                    headers = mapOf(DispatchHeader.EVENT_ID to eventId.toString()),
                )

                eventually(30.seconds) {
                    val count =
                        app.database.transact {
                            InboxMessageTable
                                .selectAll()
                                .where { InboxMessageTable.eventId eq eventId }
                                .count()
                        }
                    count shouldBe 1L
                }
            }
        }
    })
