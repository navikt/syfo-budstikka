package no.nav.budstikka.contract

/**
 * Cases (saker) in Min side – arbeidsgiver. Every notification and calendar event there belongs to a
 * case. Budstikka owns the cases: the producer only chooses a [Type], and Budstikka puts the content
 * in that type's case for the recipient, creating it when needed. For a Nærmeste leder the case
 * belongs to the leader relation, so a new leader gets a case of their own. For an Altinn resource it
 * belongs to the organisation and the Sykmeldt.
 */
object Arbeidsgiversak {
    /** The case the content belongs to; it decides the case title and merkelapp. */
    enum class Type {
        /** Dialogmøter and møtebehov. */
        DIALOGMOTE,

        /** Oppfølgingsplaner. */
        OPPFOLGINGSPLAN,
    }
}

/**
 * Producer-facing inputs for a calendar event (kalenderavtale). The event's dispatch
 * `reference` is its downstream identity: create it once and update it with the same `reference`.
 * Start and end time cannot be changed downstream; a new time is a new event, and the old one is set
 * to [Tilstand.AVLYST].
 */
object Kalenderavtale {
    /** Calendar event state shown to the leader. */
    enum class Tilstand {
        /** Waiting for the employer's response. This is the default. */
        VENTER_SVAR_FRA_ARBEIDSGIVER,

        /** The employer wants to cancel. */
        ARBEIDSGIVER_VIL_AVLYSE,

        /** The employer wants a different time or place. */
        ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED,

        /** The employer has accepted. */
        ARBEIDSGIVER_HAR_GODTATT,

        /** The event is cancelled. */
        AVLYST,

        /** The event has taken place. */
        AVHOLDT,
    }

    /** Physical meeting place shown to the leader. */
    data class Lokasjon(
        val adresse: String,
        val postnummer: String,
        val poststed: String,
    ) {
        override fun toString(): String = "Lokasjon()"
    }

    /**
     * Email to the Nærmeste leder's registered addresses. Budstikka resolves the addresses at send
     * time. Use [plainText] for ordinary text, which is escaped before delivery, or [html] for static,
     * reviewed markup that is forwarded unchanged; never interpolate untrusted values into it.
     */
    @ConsistentCopyVisibility
    data class EmailNotification private constructor(
        val title: String,
        val body: String,
        val isHtml: Boolean,
    ) {
        override fun toString(): String = "EmailNotification(isHtml=$isHtml)"

        companion object {
            /** Plain-text email; the body is escaped before delivery. */
            fun plainText(
                title: String,
                text: String,
            ): EmailNotification = EmailNotification(title = title, body = text, isHtml = false)

            /** HTML email forwarded unchanged; an explicit trust boundary owned by the producer. */
            fun html(
                title: String,
                htmlBody: String,
            ): EmailNotification = EmailNotification(title = title, body = htmlBody, isHtml = true)
        }
    }
}
