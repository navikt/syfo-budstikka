package no.nav.budstikka.contract

/**
 * Producer-facing inputs for an Arbeidsgivervarsel. Choose exactly one [Mottaker] path.
 *
 * These types deliberately describe only paths Budstikka can deliver. Tags and Altinn resources are
 * selected as plain strings by the producer and carried unchanged to the downstream recipient.
 */
object Arbeidsgivervarsel {
    /** Presentation kind for an Arbeidsgivervarsel. */
    enum class Meldingstype {
        BESKJED,
        OPPGAVE,
    }

    /** Producer-owned case identifier used for downstream grouping. */
    data class Sakstilknytning(
        val sakId: String,
    ) {
        override fun toString(): String = "Sakstilknytning()"
    }

    /** Exactly one delivery path for an Arbeidsgivervarsel. */
    sealed interface Mottaker

    /**
     * Sends to the active Nærmeste leder for [sykmeldt]. Budstikka's delivery flow ends when no
     * active leader exists. When external notification is requested, it also ends when the leader
     * has no email address. It does not fall back to another recipient.
     */
    data class NarmesteLeder(
        val sykmeldt: PersonIdentifier,
        val externalVarsling: NarmesteLederExternalVarsling? = null,
    ) : Mottaker {
        override fun toString(): String = "NarmesteLeder(hasExternalVarsling=${externalVarsling != null})"
    }

    /** Sends to everyone with the selected Altinn resource at the organisation. */
    data class AltinnRessurs(
        val resource: String,
        val externalVarsling: AltinnExternalVarsling? = null,
    ) : Mottaker {
        override fun toString(): String = "AltinnRessurs(hasExternalVarsling=${externalVarsling != null})"
    }

    /** External notification texts required by the Nærmeste leder email path. */
    data class NarmesteLederExternalVarsling(
        val emailTitle: String,
        val emailText: String,
    ) {
        override fun toString(): String = "NarmesteLederExternalVarsling()"
    }

    /** External notification texts required by the Altinn-resource path. */
    data class AltinnExternalVarsling(
        val emailTitle: String,
        val emailText: String,
        val smsText: String,
    ) {
        override fun toString(): String = "AltinnExternalVarsling()"
    }
}
