package no.nav.budstikka.domain.dispatch

/** Selvstendige testkonstanter for kontrakt-modulen (uavhengig av app-modulens fakes). */
val TEST_SYKMELDT_2 = PersonIdentifier("12345678901")
val TEST_ORGNUMMER = Orgnummer("987654321")

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
