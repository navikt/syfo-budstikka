package no.nav.budstikka.contract

/**
 * Producer-facing inputs for a case in Arbeidsgivernotifikasjoner. The producer owns the case: it
 * chooses a stable `caseId` (used downstream as grupperingsid), creates the case once and decides
 * every status change. Budstikka resolves the recipient at send time and keeps no case state.
 */
object Arbeidsgiversak {
    /** Case status shown to the recipient. */
    enum class Status {
        /** The case has been received and is waiting for processing. */
        RECEIVED,

        /** The case is being processed. */
        IN_PROGRESS,

        /** The case is complete and is shown with lower priority. */
        DONE,
    }

    /** Exactly one recipient path for a case. */
    sealed interface Recipient

    /**
     * The active Nærmeste leder for [sykmeldt] at send time. Content linked to the case must reach
     * the same leader, so a leader change after the case is created makes new content for it fail.
     */
    data class NarmesteLeder(
        val sykmeldt: PersonIdentifier,
    ) : Recipient {
        override fun toString(): String = "NarmesteLeder()"
    }

    /** Everyone with the selected Altinn resource at the organisation. */
    data class AltinnResource(
        val resource: String,
    ) : Recipient {
        override fun toString(): String = "AltinnResource()"
    }
}

/**
 * Producer-facing inputs for a calendar event in a case. The event's dispatch `reference` is its
 * downstream identity: create it once and update it with the same `reference`. Start and end time
 * cannot be changed downstream; a new time is a new event, and the old one is set to
 * [State.CANCELLED].
 */
object Kalenderavtale {
    /** Calendar event state shown to the leader. */
    enum class State {
        /** Waiting for the employer's response. This is the default. */
        AWAITING_RESPONSE,

        /** The employer wants to cancel. */
        EMPLOYER_WANTS_TO_CANCEL,

        /** The employer wants a different time or place. */
        EMPLOYER_WANTS_TO_RESCHEDULE,

        /** The employer has accepted. */
        EMPLOYER_ACCEPTED,

        /** The event is cancelled. */
        CANCELLED,

        /** The event has taken place. */
        HELD,
    }

    /** Physical meeting place shown to the leader. */
    data class Location(
        val address: String,
        val postalCode: String,
        val city: String,
    ) {
        override fun toString(): String = "Location()"
    }

    /**
     * Email to the leader's registered addresses. Budstikka resolves the addresses at send time.
     * Use [plainText] for ordinary text, which is escaped before delivery, or [html] for static,
     * reviewed markup that is forwarded unchanged; never interpolate untrusted values into it.
     */
    @ConsistentCopyVisibility
    data class LeaderEmail private constructor(
        val title: String,
        val body: String,
        val isHtml: Boolean,
    ) {
        override fun toString(): String = "LeaderEmail(isHtml=$isHtml)"

        companion object {
            /** Plain-text email; the body is escaped before delivery. */
            fun plainText(
                title: String,
                text: String,
            ): LeaderEmail = LeaderEmail(title = title, body = text, isHtml = false)

            /** HTML email forwarded unchanged; an explicit trust boundary owned by the producer. */
            fun html(
                title: String,
                htmlBody: String,
            ): LeaderEmail = LeaderEmail(title = title, body = htmlBody, isHtml = true)
        }
    }
}
