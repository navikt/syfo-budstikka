# Pagination and input-validation examples

Design patterns for future `no.nav.budstikka` routes. The repository does not
currently have this pagination or `ApiErrorException`; use them only when the
API design is selected and the `StatusPages` contract in `error-handling.md` is
introduced at the same time.

## Pagination

### PaginatedResponse

```kotlin
@Serializable
data class PaginatedResponse<T>(
    val innhold: List<T>,
    val side: Int,
    val antallPerSide: Int,
    val totaltAntall: Long,
    val totaltAntallSider: Int,
)

get("/api/v1/vedtak") {
    val side = call.queryParameters["side"]?.toIntOrNull() ?: 0
    val antall = call.queryParameters["antall"]?.toIntOrNull() ?: 20
    if (antall > 100) throw ApiErrorException.BadRequestException("Maks 100 per side")
    val result = vedtakService.findAll(offset = side * antall, limit = antall)
    call.respond(result)
}
```

### Example response

```json
{
  "innhold": [],
  "side": 0,
  "antallPerSide": 20,
  "totaltAntall": 142,
  "totaltAntallSider": 8
}
```

## Input validation

### Example route with validation

```kotlin
@Serializable
data class CreateVedtakRequest(val brukerId: String, val beskrivelse: String? = null, val type: VedtakType)

post("/api/v1/vedtak") {
    val request = call.receive<CreateVedtakRequest>()
    if (request.brukerId.isBlank()) throw ApiErrorException.BadRequestException("brukerId kan ikke være tom")
    request.beskrivelse?.let { if (it.length > 500) throw ApiErrorException.BadRequestException("beskrivelse maks 500 tegn") }
    val vedtak = vedtakService.create(request)
    call.response.header("Location", "/api/v1/vedtak/${vedtak.id}")
    call.respond(HttpStatusCode.Created, vedtak.toDto())
}
```

Missing required fields in a request body are caught by
`ContentNegotiation`/serialisation and mapped to `INVALID_FORMAT` in
`determineApiError()`. Validate business rules explicitly with an early return
as above.
