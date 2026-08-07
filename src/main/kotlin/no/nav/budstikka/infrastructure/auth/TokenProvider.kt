package no.nav.budstikka.infrastructure.auth

/**
 * Supplies machine-to-machine bearer tokens. `target` is an Entra ID scope in the form
 * `api://<cluster>.<namespace>.<app>/.default`.
 */
interface TokenProvider {
    /** Returns a valid, non-expired bearer token for `target`. */
    suspend fun token(target: String): String
}
