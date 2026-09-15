package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.esyfo.observability.Event

internal object InboxRepositoryLogEvents {
    val poisonRowsFailed =
        Event<PoisonInboxContext>(
            name = "inbox.poison_rows.failed",
            level = ApplicationLogLevel.WARN,
            message = "Failed poison inbox message(s) after reaching max attempts",
            operation = "inbox.fail_poison_rows",
            errorCode = "INBOX_MAX_ATTEMPTS_REACHED",
            fields =
                mapOf(
                    MdcKeys.POISON_COUNT to { it.poisonCount },
                    MdcKeys.MAX_ATTEMPTS to { it.maxAttempts },
                ),
        )
}

internal data class PoisonInboxContext(
    val poisonCount: Int,
    val maxAttempts: Int,
)
