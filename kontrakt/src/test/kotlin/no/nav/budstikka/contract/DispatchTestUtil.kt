// Helpers for the wire-level tests; they take and return the raw envelope by definition.
@file:OptIn(InternalBudstikkaWire::class)

package no.nav.budstikka.contract

fun envelope(content: DispatchContent) =
    Dispatch(
        reference = "ref-123",
        content = content,
    )

fun roundtrip(content: DispatchContent): Dispatch {
    val original = envelope(content)
    val json = dispatchJson.encodeToString(original)
    return dispatchJson.decodeFromString<Dispatch>(json)
}
