package no.nav.budstikka.fakes

import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.Varseltype

fun microfrontendEnable(): MicrofrontendEnable =
    MicrofrontendEnable(
        personIdentifier = TEST_SYKMELDT,
        mikrofrontendId = "sykmeldt-overview",
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
