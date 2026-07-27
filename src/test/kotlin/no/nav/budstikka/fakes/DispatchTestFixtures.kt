package no.nav.budstikka.fakes

import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.Varseltype
import java.util.UUID

/** Shared hydrated inbox row for tests. Defaults are sufficient; override only what the test cares about. */
fun inboxMessage(
    eventId: UUID = UUID.randomUUID(),
    reference: String = "ref-1",
    content: DispatchContent = microfrontendEnable(),
): InboxMessage = InboxMessage(eventId = eventId, reference = reference, content = content)

fun microfrontendEnable(): MicrofrontendEnable =
    MicrofrontendEnable(
        personIdentifier = TEST_SYKMELDT,
        microfrontendId = "sykmeldt-overview",
    )

fun microfrontendDraft(reference: String = "ununsed") =
    DeliveryDraft(
        reference = reference,
        operation = Operation.CREATE,
        channel = Channel.MICROFRONTEND,
        recipient = Recipient.Person(TEST_SYKMELDT),
        content = microfrontendEnable(),
    )

fun brukervarselDraft(): DeliveryDraft =
    DeliveryDraft(
        reference = "unused",
        operation = Operation.CREATE,
        channel = Channel.BRUKERVARSEL,
        recipient = Recipient.Person(TEST_SYKMELDT),
        content = BrukervarselCreate(personIdentifier = TEST_SYKMELDT, varseltype = Varseltype.BESKJED, text = "Hei"),
    )
