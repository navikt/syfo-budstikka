package no.nav.budstikka.fakes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.port.DistributionRequest
import no.nav.budstikka.application.port.DistributionResponse
import no.nav.budstikka.application.port.DistributionType
import java.util.UUID

class FakeDocumentDistributorTest :
    FunSpec({
        test("accepts document distribution locally and records the request") {
            val fake = FakeDocumentDistributor()
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000701")
            val request =
                DistributionRequest(
                    journalpostId = "jp-1",
                    distributionType = DistributionType.VIKTIG,
                    eventId = eventId,
                    forceCentralPrint = true,
                )

            val response = fake.distribute(request)

            response shouldBe DistributionResponse.Ok(orderId = "local-$eventId")
            fake.requests.shouldContainExactly(request)
        }
    })
