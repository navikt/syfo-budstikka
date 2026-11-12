package no.nav.budstikka.contract

/**
 * Saker in Min side – arbeidsgiver. Every notification and kalenderavtale there belongs to a
 * sak. Budstikka owns the saker: the producer chooses a [Type], and Budstikka puts the content
 * in that type's sak for the recipient, creating it when needed. For a Nærmeste leder the sak
 * belongs to the leader relation, so a new leader gets a sak of their own. For an Altinn resource the
 * producer gives a stable `sakId` per organisation and Sykmeldt, since there is no relation to key on.
 */
object Arbeidsgiversak {
    /** The sak the content belongs to; it decides the sak title and merkelapp. */
    enum class Type {
        /** Dialogmøter and møtebehov. */
        DIALOGMOTE,

        /** Oppfølgingsplaner. */
        OPPFOLGINGSPLAN,
    }

    /**
     * The saksstatus shown to the employer, set only when a producer asks for it. A sak is shared,
     * so the latest status wins; it can go back, for example when a new meeting is called.
     */
    enum class Status {
        /** Received; the status of a new sak. */
        MOTTATT,

        /** Something is going on. */
        UNDER_BEHANDLING,

        /** Nothing is pending for now. */
        FERDIG,
    }
}

/** Producer-facing inputs for a kalenderavtale; see `Budstikka.kalenderavtaleCreate`. */
object Kalenderavtale {
    /** Kalenderavtale state shown to the leader. */
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
