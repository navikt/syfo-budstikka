package no.nav.budstikka.contract

import kotlin.time.Instant

/**
 * A case (sak) in Min side – arbeidsgiver. Every notification and calendar event there belongs to a
 * case, so the producer describes the case on each operation and Budstikka makes sure it exists
 * before the content is created. The producer owns the case: it chooses a stable [sakId] and decides
 * every status change. Budstikka keeps no case state.
 *
 * For a Nærmeste leder recipient, the case downstream belongs to the current leader relation: a new
 * leader gets a case of their own for the same [sakId], and the previous leader loses access through
 * Arbeidsgivernotifikasjoner's own access control.
 *
 * @property sakId your stable case identity. Never use a personident.
 * @property title the case title shown to the recipient, at most 140 characters. It is set when
 *   the case is created and cannot be changed afterwards.
 * @property link optional target for the case; omit to use the case page in Min side arbeidsgiver.
 * @property hardDeleteAt when Arbeidsgivernotifikasjoner permanently deletes the case and all
 *   content in it. Omit to use the four-month retention period when the case is created.
 *   [Budstikka.arbeidsgiversakStatus] replaces an earlier time with this one.
 */
data class Arbeidsgiversak(
    val sakId: String,
    val title: String,
    val link: String? = null,
    val hardDeleteAt: Instant? = null,
) {
    /** Omits the case identity, title and link. */
    override fun toString(): String = "Arbeidsgiversak(hasLink=${link != null}, hasHardDeleteAt=${hardDeleteAt != null})"

    /** Case status in Arbeidsgivernotifikasjoner. */
    enum class Status {
        /** The case is received and waiting for processing. */
        MOTTATT,

        /** The case is being processed. */
        UNDER_BEHANDLING,

        /** The case is complete and is shown with lower priority. */
        FERDIG,
    }

    /** Exactly one recipient path for a case. */
    sealed interface Recipient

    /** The active Nærmeste leder for [sykmeldt] at send time. */
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
 * Producer-facing inputs for a calendar event (kalenderavtale) in a case. The event's dispatch
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
