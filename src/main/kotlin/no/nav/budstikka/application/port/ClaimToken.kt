package no.nav.budstikka.application.port

import java.util.UUID

@JvmInline
value class ClaimToken(
    val value: UUID,
) {
    companion object {
        fun generate(): ClaimToken = ClaimToken(UUID.randomUUID())
    }
}
