package no.nav.budstikka.domain.dispatch

import java.util.UUID

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
