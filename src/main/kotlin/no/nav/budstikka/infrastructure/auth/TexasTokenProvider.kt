package no.nav.budstikka.infrastructure.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import no.nav.budstikka.infrastructure.auth.config.TexasConfig
import sharedJson
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [TokenProvider] for the NAIS Texas token sidecar: exchanges a machine-to-machine token
 * (Entra ID `client_credentials`) per target and caches it until just before expiry.
 *
 * Texas never returns an expired token, but caching here saves a sidecar round trip on every
 * downstream call. Tokens are secrets: never log them or include them in exception messages
 * (only the status code on failure).
 */
class TexasTokenProvider(
    private val httpClient: HttpClient,
    private val config: TexasConfig,
    private val clock: Clock = Clock.System,
) : TokenProvider {
    private val cache = ConcurrentHashMap<String, CachedToken>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun token(target: String): String {
        validCachedToken(target)?.let { return it }
        // Per-target lock prevents token request stampede.
        return locks.getOrPut(target) { Mutex() }.withLock {
            validCachedToken(target) ?: fetchToken(target).also { cache[target] = it }.accessToken
        }
    }

    private fun validCachedToken(target: String): String? = cache[target]?.takeIf { it.isValidAt(clock.now()) }?.accessToken

    private suspend fun fetchToken(target: String): CachedToken {
        val response =
            httpClient.submitForm(
                url = config.tokenEndpoint,
                formParameters =
                    parameters {
                        append("identity_provider", config.identityProvider)
                        append("target", target)
                    },
            )
        check(response.status.isSuccess()) {
            "Texas token request failed with status ${response.status.value}"
        }
        val body =
            try {
                sharedJson.decodeFromString<TexasTokenResponse>(response.bodyAsText())
            } catch (_: SerializationException) {
                throw IllegalStateException("Invalid Texas token response")
            }
        return CachedToken(
            accessToken = body.accessToken,
            expiresAt = clock.now() + body.expiresInSeconds.seconds,
        )
    }

    private data class CachedToken(
        val accessToken: String,
        val expiresAt: Instant,
    ) {
        fun isValidAt(now: Instant): Boolean = now < expiresAt - EXPIRY_LEEWAY
    }

    companion object {
        // Renew slightly before expiry to handle skew and call latency.
        private val EXPIRY_LEEWAY: Duration = 30.seconds
    }
}

@Serializable
private data class TexasTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresInSeconds: Long,
    @SerialName("token_type") val tokenType: String = "Bearer",
)
