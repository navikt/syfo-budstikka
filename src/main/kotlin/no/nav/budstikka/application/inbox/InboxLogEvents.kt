package no.nav.budstikka.application.inbox

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.esyfo.observability.Event

internal object InboxLogEvents {
    val claimSkipped =
        Event<Unit>(
            name = "inbox.claim.skipped",
            level = ApplicationLogLevel.WARN,
            message = "Skipping inbox message because the row is no longer claimable or has spent its attempts",
            operation = "inbox.process",
            errorCode = "INBOX_NOT_CLAIMABLE",
        )
}
