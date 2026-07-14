package no.nav.budstikka.infrastructure.auth

/**
 * Gjenbrukbar utgående token-søm (#48): kanal-klienter (PdlClient m.fl.) henter et
 * maskin-til-maskin bearer-token herfra i stedet for å håndtere token-veksling selv.
 *
 * [target] er den tiltenkte mottakeren (audience) for det nedstrøms API-et, på Entra ID
 * scope-form `api://<cluster>.<namespace>.<app>/.default`.
 */
interface TokenProvider {
    /** Returnerer et gyldig (ikke-utløpt) bearer-token for [target]. */
    suspend fun token(target: String): String
}
