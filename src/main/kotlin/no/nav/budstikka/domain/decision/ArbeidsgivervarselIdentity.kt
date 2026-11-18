package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.Orgnummer
import java.util.UUID

/**
 * Fager's `eksternId` for a notification with a sakstype. The same organisation and reference
 * always give the same id, whichever dispatch, leader or attempt sends it, so closing can find it
 * later. The length prefix keeps the organisation number and the reference apart whatever they
 * contain. Do not change the string: every existing notification would get a new id and could no
 * longer be closed.
 */
internal fun referenceNotificationId(
    orgnummer: Orgnummer,
    reference: String,
): String =
    UUID
        .nameUUIDFromBytes(
            "budstikka:arbeidsgivervarsel:v1:${orgnummer.value.length}:${orgnummer.value}:$reference".toByteArray(Charsets.UTF_8),
        ).toString()
