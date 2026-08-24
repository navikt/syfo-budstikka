package no.nav.budstikka.contract

/**
 * Producer-facing inputs for an Arbeidsgivervarsel. Choose exactly one [Recipient] path.
 *
 * These types deliberately describe only paths Budstikka can deliver. Tags and Altinn resources are
 * selected as plain strings by the producer and carried unchanged to the downstream recipient.
 */
object Arbeidsgivervarsel {
    /** Presentation kind for an Arbeidsgivervarsel. */
    enum class MessageType {
        BESKJED,
        OPPGAVE,
    }

    /** Producer-owned case identifier used for downstream grouping. */
    data class CaseAssociation(
        val caseId: String,
    ) {
        override fun toString(): String = "CaseAssociation()"
    }

    /** Exactly one delivery path for an Arbeidsgivervarsel. */
    sealed interface Recipient

    /**
     * Sends to the active Nærmeste leder for [sykmeldt]. Budstikka's delivery flow ends when no
     * active leader exists. When external notification is requested, it also ends when the leader
     * has no email address. It does not fall back to another recipient.
     */
    data class NarmesteLeder(
        val sykmeldt: PersonIdentifier,
        val externalNotification: NarmesteLederExternalNotification? = null,
    ) : Recipient {
        override fun toString(): String = "NarmesteLeder(hasExternalNotification=${externalNotification != null})"
    }

    /** Sends to everyone with the selected Altinn resource at the organisation. */
    data class AltinnResource(
        val resource: String,
        val externalNotification: AltinnExternalNotification? = null,
    ) : Recipient {
        override fun toString(): String = "AltinnResource(hasExternalNotification=${externalNotification != null})"
    }

    /** External notification texts required by the Nærmeste leder email path. */
    data class NarmesteLederExternalNotification(
        val emailTitle: String,
        val emailText: String,
    ) {
        override fun toString(): String = "NarmesteLederExternalNotification()"
    }

    /** External notification texts required by the Altinn-resource path. */
    data class AltinnExternalNotification(
        val emailTitle: String,
        val emailText: String,
        val smsText: String,
    ) {
        override fun toString(): String = "AltinnExternalNotification()"
    }
}
