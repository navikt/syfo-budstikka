package no.nav.budstikka.domain.formidling

import java.util.UUID

/** Delte testhjelpere for formidlingskontrakten – konvolutt-bygging og (de)serialiserings-rundtur. */

fun envelope(content: Formidlingsinnhold) =
    Formidling(
        eventId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        referanse = "ref-123",
        content = content,
    )

fun rundtur(content: Formidlingsinnhold): Formidling {
    val original = envelope(content)
    val json = formidlingJson.encodeToString(original)
    return formidlingJson.decodeFromString<Formidling>(json)
}
