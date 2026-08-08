# Code examples — Pagination and input validation

Team standard patterns for `no.nav.budstikka` Ktor routes. Validate early and throw `ApiErrorException` — the StatusPages plugin (see `error-handling.md`) produces the right error response.

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
    request.beskrivelse?.let { if (it.length > 500) throw ApiErrorException.BadRequestException("beskrivelse kan være maks 500 tegn") }
    val vedtak = vedtakService.create(request)
    call.response.header("Location", "/api/v1/vedtak/${vedtak.id}")
    call.respond(HttpStatusCode.Created, vedtak.toDto())
}
```

Missing required fields in the request body are caught by `ContentNegotiation`/serialization and mapped to `INVALID_FORMAT` in `determineApiError()`. Your own business rules are validated explicitly with an early return as above.
