package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.Orgnummer
import java.util.UUID

/** Stable address within the arbeidsgivervarsel namespace; independent of dispatch ids and leaders. */
internal fun referenceNotificationId(
    orgnummer: Orgnummer,
    reference: String,
): String =
    UUID
        .nameUUIDFromBytes(
            "budstikka:arbeidsgivervarsel:v1:${orgnummer.value.length}:${orgnummer.value}:$reference".toByteArray(Charsets.UTF_8),
        ).toString()

/** Pins the address before the first external call; old producers keep their event-based identity. */
internal fun materializedNotificationId(
    content: DispatchContent,
    reference: String,
    eventId: UUID,
): String? =
    when (content) {
        is ArbeidsgivervarselCreate ->
            when (content.identity) {
                ArbeidsgivervarselCreate.Identity.EVENT_ID -> eventId.toString()
                ArbeidsgivervarselCreate.Identity.REFERENCE -> referenceNotificationId(content.orgnummer, reference)
            }
        is ArbeidsgivervarselInactivate -> content.sakstype?.let { referenceNotificationId(content.orgnummer, reference) }
        else -> null
    }
