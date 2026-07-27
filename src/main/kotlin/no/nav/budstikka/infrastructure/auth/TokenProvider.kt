package no.nav.budstikka.infrastructure.auth

/**
 * Reusable outgoing-token seam (#48): channel clients (PdlClient and others) get a
 * machine-to-machine bearer token here instead of managing token exchange themselves.
 *
 * [target] is the intended audience for the downstream API, in Entra ID scope form
 * `api://<cluster>.<namespace>.<app>/.default`.
 */
interface TokenProvider {
    /** Returns a valid, non-expired bearer token for [target]. */
    suspend fun token(target: String): String
}
