package no.nav.budstikka.domain.dispatch

import java.util.UUID

/** Delte testhjelpere for formidlingskontrakten – konvolutt-bygging og (de)serialiserings-rundtur. */

fun envelope(content: DispatchContent) =
    Dispatch(
        eventId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        reference = "ref-123",
        content = content,
    )

fun rundtur(content: DispatchContent): Dispatch {
    val original = envelope(content)
    val json = dispatchJson.encodeToString(original)
    return dispatchJson.decodeFromString<Dispatch>(json)
}
