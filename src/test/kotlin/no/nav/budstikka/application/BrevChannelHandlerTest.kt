package no.nav.budstikka.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DistributionRequest
import no.nav.budstikka.application.port.DistributionResponse
import no.nav.budstikka.application.port.DocumentDistributor
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import java.util.UUID
import no.nav.budstikka.application.port.DistributionType as PortDistributionType
import no.nav.budstikka.domain.dispatch.DistributionType as BrevDistributionType

class BrevChannelHandlerTest :
    FunSpec({
        test("distributes BrevCreate with central print and returns Sent") {
            val distributor = RecordingDocumentDistributor(DistributionResponse.Ok(orderId = "order-1"))
            val handler = BrevChannelHandler(distributor)
            val payload =
                BrevCreate(
                    personIdentifier = PersonIdentifier("12345678901"),
                    journalpostId = "jp-1",
                )

            val outcome = handler.handle(delivery(payload))

            outcome shouldBe DeliveryOutcome.Sent
            distributor.requests.shouldHaveSize(1)
            distributor.requests.single() shouldBe
                DistributionRequest(
                    journalpostId = "jp-1",
                    distributionType = PortDistributionType.VIKTIG,
                    eventId = INBOX_EVENT_ID,
                    forceCentralPrint = true,
                )
        }

        test("maps OTHER distribution type to ANNET") {
            val distributor = RecordingDocumentDistributor(DistributionResponse.Ok(orderId = "order-1"))
            val handler = BrevChannelHandler(distributor)
            val payload =
                BrevCreate(
                    personIdentifier = PersonIdentifier("12345678901"),
                    journalpostId = "jp-2",
                    distributionType = BrevDistributionType.OTHER,
                )

            handler.handle(delivery(payload))

            distributor.requests.single().distributionType shouldBe PortDistributionType.ANNET
        }

        test("returns Failed when document distribution is rejected permanently") {
            val distributor = RecordingDocumentDistributor(DistributionResponse.NotOk("Journalposten ble ikke funnet"))
            val handler = BrevChannelHandler(distributor)
            val payload =
                BrevCreate(
                    personIdentifier = PersonIdentifier("12345678901"),
                    journalpostId = "jp-1",
                )

            val outcome = handler.handle(delivery(payload))

            outcome.shouldBeInstanceOf<DeliveryOutcome.Failed>()
            outcome.reason shouldBe "Journalposten ble ikke funnet"
            distributor.requests.shouldHaveSize(1)
        }

        test("returns Failed without distributing when payload is not BrevCreate") {
            val distributor = RecordingDocumentDistributor(DistributionResponse.Ok(orderId = "order-1"))
            val handler = BrevChannelHandler(distributor)
            val payload =
                MicrofrontendEnable(
                    personIdentifier = PersonIdentifier("12345678901"),
                    microfrontendId = "syfo-microfrontend",
                )

            val outcome = handler.handle(delivery(payload))

            outcome.shouldBeInstanceOf<DeliveryOutcome.Failed>()
            outcome.reason shouldContain "MicrofrontendEnable"
            distributor.requests.shouldBeEmpty()
        }

        test("wraps transient distributor failures with BREV context") {
            val handler = BrevChannelHandler(ThrowingDocumentDistributor())
            val payload =
                BrevCreate(
                    personIdentifier = PersonIdentifier("12345678901"),
                    journalpostId = "jp-1",
                )

            val error = shouldThrow<ChannelHandlerFailure> { handler.handle(delivery(payload)) }

            error.message shouldContain "BREV channel failed"
            error.cause.shouldBeInstanceOf<IllegalStateException>()
            error.stackTrace.any { it.className.contains("BrevChannelHandler") } shouldBe true
        }
    })

private val DELIVERY_ID = UUID.fromString("00000000-0000-0000-0000-000000000601")
private val INBOX_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000602")

private class RecordingDocumentDistributor(
    private val response: DistributionResponse,
) : DocumentDistributor {
    val requests = mutableListOf<DistributionRequest>()

    override suspend fun distribute(request: DistributionRequest): DistributionResponse {
        requests.add(request)
        return response
    }
}

private class ThrowingDocumentDistributor : DocumentDistributor {
    override suspend fun distribute(request: DistributionRequest): DistributionResponse = error("Connection refused")
}

private fun delivery(payload: DispatchContent): ClaimedDelivery =
    ClaimedDelivery(
        id = DELIVERY_ID,
        inboxEventId = INBOX_EVENT_ID,
        reference = "brev-reference",
        channel = Channel.BREV,
        payload = payload,
    )
