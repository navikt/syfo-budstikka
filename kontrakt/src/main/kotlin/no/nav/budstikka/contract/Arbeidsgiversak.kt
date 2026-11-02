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
