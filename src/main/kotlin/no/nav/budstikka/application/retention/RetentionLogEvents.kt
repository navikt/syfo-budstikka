package no.nav.budstikka.application.retention

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.esyfo.observability.Event

internal object RetentionLogEvents {
    val unprocessedInboxDeleted =
        Event<RetentionCounts>(
            name = "retention.cleanup.inbox.unprocessed.deleted",
            level = ApplicationLogLevel.WARN,
            message = "Retention cleanup deleted unprocessed inbox rows past the absolute retention ceiling",
            operation = "retention.cleanup",
            fields =
                mapOf(
                    "inbox_deleted" to { it.inboxMessages },
                    "inbox_unprocessed_deleted" to { it.unprocessedInboxMessages },
                ),
        )
}
