package no.nav.budstikka.domain.formidling

import kotlinx.serialization.encodeToString
import java.util.UUID

/** Delte testhjelpere for formidlingskontrakten – konvolutt-bygging og (de)serialiserings-rundtur. */

fun envelope(innhold: Formidlingsinnhold) =
    Formidling(
        eventId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        referanse = "ref-123",
        innhold = innhold,
    )

fun rundtur(innhold: Formidlingsinnhold): Formidling {
    val original = envelope(innhold)
    val json = formidlingJson.encodeToString(original)
    return formidlingJson.decodeFromString<Formidling>(json)
}
