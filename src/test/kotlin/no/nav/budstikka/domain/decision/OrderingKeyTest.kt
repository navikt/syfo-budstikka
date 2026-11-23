package no.nav.budstikka.domain.decision

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.Varseltype
import no.nav.budstikka.fakes.TEST_SYKMELDT

private const val MICROFRONTEND_ID = "syfo-dialog"

class OrderingKeyTest :
    FunSpec({
        fun processed(vararg contents: DispatchContent) =
            Decision.Processed(contents.mapIndexed { index, content -> content.toDeliveryDraft("ref-$index") })

        test("is the sykmeldt and microfrontend for microfrontend commands") {
            val key = OrderingKey.Microfrontend(TEST_SYKMELDT, MICROFRONTEND_ID)

            processed(MicrofrontendEnable(TEST_SYKMELDT, MICROFRONTEND_ID)).orderingKey() shouldBe key
            processed(MicrofrontendDisable(TEST_SYKMELDT, MICROFRONTEND_ID)).orderingKey() shouldBe key
        }

        test("is absent for other content") {
            processed(BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "text")).orderingKey().shouldBeNull()
        }

        test("rejects a decision that changes two microfrontends") {
            shouldThrow<IllegalStateException> {
                processed(
                    MicrofrontendEnable(TEST_SYKMELDT, MICROFRONTEND_ID),
                    MicrofrontendDisable(TEST_SYKMELDT, "other"),
                ).orderingKey()
            }
        }

        test("omits the person and microfrontend from its string form") {
            OrderingKey.Microfrontend(TEST_SYKMELDT, MICROFRONTEND_ID).toString() shouldBe "OrderingKey.Microfrontend()"
        }
    })
