package no.nav.budstikka.infrastructure.foundation

import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.foundation.DeathLookup

/**
 * Midlertidig adapter før ekte PDL-oppslag kobles inn: alle behandles som levende.
 */
class NoopDeathLookup : DeathLookup {
    override suspend fun isDead(ident: PersonIdentifier): Boolean = false
}
