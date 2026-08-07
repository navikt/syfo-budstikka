package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchHeader
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.dispatchJson
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import no.nav.budstikka.testsupport.BudstikkaTestApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@Tags("E2E")
class DispatchToInboxE2ESpec :
    FunSpec({
        test("produced Dispatch is ingested into inbox_message by the real consumer") {
            BudstikkaTestApp.start().use { app ->
                val eventId = UUID.randomUUID()
                val dispatch =
                    Dispatch(
                        reference = "e2e-ref-1",
                        content = MicrofrontendEnable(TEST_SYKMELDT, "syfo-microfrontend"),
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
