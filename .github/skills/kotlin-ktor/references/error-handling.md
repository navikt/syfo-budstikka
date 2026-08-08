# Error handling — Ktor StatusPages (complete implementation)

Team standard for a uniform error contract in `no.nav.budstikka` Ktor services. Clients always get the same JSON shape, and unexpected errors do not leak a stacktrace.

The `message` field is consumer-facing, so its text is Norwegian Bokmål (see
`docs/agents/language-policy.md` and `/klarsprak`). Identifiers, `type` values
and log lines stay English.

## ApiError and ErrorType

```kotlin
open class ApiError(
    val status: HttpStatusCode,
    val type: ErrorType,
    open val message: String,
    open val path: String? = null,
    val timestamp: Instant = Instant.now(),
)

enum class ErrorType { AUTHENTICATION_ERROR, AUTHORIZATION_ERROR, NOT_FOUND, INTERNAL_SERVER_ERROR, BAD_REQUEST, INVALID_FORMAT, CONFLICT }
```

## ApiErrorException — sealed class

```kotlin
sealed class ApiErrorException(message: String, val type: ErrorType, cause: Throwable?) : RuntimeException(message, cause) {
    abstract fun toApiError(path: String): ApiError
    class ForbiddenException(val errorMessage: String = "Ingen tilgang", cause: Throwable? = null, type: ErrorType = ErrorType.AUTHORIZATION_ERROR) : ApiErrorException(errorMessage, type, cause) {
        override fun toApiError(path: String) = ApiError(HttpStatusCode.Forbidden, type, errorMessage, path)
    }
    class BadRequestException(val errorMessage: String = "Ugyldig forespørsel", cause: Throwable? = null, type: ErrorType = ErrorType.BAD_REQUEST) : ApiErrorException(errorMessage, type, cause) {
        override fun toApiError(path: String) = ApiError(HttpStatusCode.BadRequest, type, errorMessage, path)
    }
    class NotFoundException(val errorMessage: String = "Fant ikke ressursen", cause: Throwable? = null, type: ErrorType = ErrorType.NOT_FOUND) : ApiErrorException(errorMessage, type, cause) {
        override fun toApiError(path: String) = ApiError(HttpStatusCode.NotFound, type, errorMessage, path)
    }
    class InternalServerErrorException(val errorMessage: String = "Det oppsto en uventet feil", cause: Throwable? = null, type: ErrorType = ErrorType.INTERNAL_SERVER_ERROR) : ApiErrorException(errorMessage, type, cause) {
        override fun toApiError(path: String) = ApiError(HttpStatusCode.InternalServerError, type, errorMessage, path)
    }
    class UnauthorizedException(val errorMessage: String = "Ikke autentisert", cause: Throwable? = null, type: ErrorType = ErrorType.AUTHENTICATION_ERROR) : ApiErrorException(errorMessage, type, cause) {
        override fun toApiError(path: String) = ApiError(HttpStatusCode.Unauthorized, type, errorMessage, path)
    }
}
```

## installStatusPages()

```kotlin
fun Application.installStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logException(call, cause)
            val apiError = determineApiError(cause, call.request.path())
            call.respond(apiError.status, apiError)
        }
    }
}
```

## determineApiError()

```kotlin
fun determineApiError(cause: Throwable, path: String): ApiError = when (cause) {
    is BadRequestException -> cause.toApiError(path)
    is NotFoundException -> cause.toApiError(path)
    is ApiErrorException -> cause.toApiError(path)
    else -> ApiError(HttpStatusCode.InternalServerError, ErrorType.INTERNAL_SERVER_ERROR, "Det oppsto en uventet feil", path)
}

fun BadRequestException.toApiError(path: String?): ApiError {
    val rootCause = this.rootCause()
    return if (rootCause is MissingFieldException) {
        ApiErrorException.BadRequestException("Ugyldig forespørsel. Mangler påkrevd felt: ${rootCause.missingFields.joinToString()}", type = ErrorType.INVALID_FORMAT).toApiError(path ?: "")
    } else {
        ApiError(status = HttpStatusCode.BadRequest, type = ErrorType.BAD_REQUEST, message = this.message ?: "Ugyldig forespørsel", path = path)
    }
}

fun NotFoundException.toApiError(path: String?): ApiError = ApiError(
    status = HttpStatusCode.NotFound, type = ErrorType.NOT_FOUND, message = this.message ?: "Fant ikke ressursen", path = path,
)

fun Throwable.rootCause(): Throwable {
    var root: Throwable = this
    while (root.cause != null && root.cause != root) root = root.cause!!
    return root
}
```

## Logging

Expected client errors (`ApiErrorException`) are logged at `warn`, unexpected ones at `error`. Log the callId, never the raw payload with PII.

```kotlin
private fun logException(call: ApplicationCall, cause: Throwable) {
    val callId = call.callId
    val logMessage = "Caught exception, callId=$callId"
    val log = call.application.log
    when (cause) {
        is ApiErrorException -> log.warn(logMessage, cause)
        else -> log.error(logMessage, cause)
    }
}
```

## apiModule() — setup

`installStatusPages()` is registered in an `Application` module. Remember to add the module to `application.conf` (`ktor.application.modules`) for it to run.

```kotlin
fun Application.apiModule() {
    installCallId()
    installContentNegotiation()
    installStatusPages()
    // ... routing
}
```

## Example error response

```json
{
  "status": 404,
  "type": "NOT_FOUND",
  "message": "Fant ingen formidling med referanse 550e8400",
  "path": "/api/v1/formidlinger/550e8400",
  "timestamp": "2025-01-15T10:30:00Z"
}
```
