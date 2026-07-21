package no.nav.budstikka.domain.dispatch

/** Delte testhjelpere for dispatch-kontrakten – konvolutt-bygging og (de)serialiserings-rundtur. */

fun envelope(content: DispatchContent) =
    Dispatch(
        reference = "ref-123",
        content = content,
    )

fun rundtur(content: DispatchContent): Dispatch {
    val original = envelope(content)
    val json = dispatchJson.encodeToString(original)
    return dispatchJson.decodeFromString<Dispatch>(json)
}
