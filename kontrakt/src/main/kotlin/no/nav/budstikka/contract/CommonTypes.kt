package no.nav.budstikka.contract

import kotlinx.serialization.Serializable

/** Presentation kind for a Brukervarsel on Min side. */
enum class Varseltype {
    /** Informational notification. */
    BESKJED,

    /** Notification that represents a task for the recipient. */
    OPPGAVE,
}

@InternalBudstikkaWire
sealed interface Brukervarsel {
    val partitionKey: String
}

@InternalBudstikkaWire
sealed interface Ledervarsel {
    val partitionKey: String
}

/**
 * Closed task-type contract for Dine Sykmeldte. [wireValue] decouples the published value from the
 * Kotlin identifier; budstikka carries the value without branching on it.
 *
 * @property wireValue Stable value delivered to Dine Sykmeldte.
 */
enum class Oppgavetype(
    val wireValue: String,
) {
    /** Invitation to a dialogue meeting. */
    DIALOGMOTE_INNKALLING("DIALOGMOTE_INNKALLING"),

    /** Reminder about a follow-up plan. */
    OPPFOLGINGSPLAN_PAAMINNELSE("OPPFOLGINGSPLAN_PAAMINNELSE"),
}

/** Carrier available for an optional external notification. */
enum class ExternalChannel {
    /** Text message. */
    SMS,

    /** Email. */
    EMAIL,
}

/**
 * Optional plain-text overrides for external notification. A `null` field leaves that channel's text
 * unspecified. Producers use the named factories; decoding remains tolerant of any channel set for
 * wire compatibility.
 *
 * The Kotlin type name is [ExternalNotification]; the serialized field on the wire is
 * `externalVarsling` (unaffected by this class having no discriminator) and is not renamed here — see
 * [BrukervarselCreate.externalVarsling]. Arbeidsgivervarsel uses recipient-specific external
 * notification types instead.
 *
 * @property channels External carriers that may receive the notification.
 * @property smsText Optional SMS body.
 * @property emailTitle Optional email subject.
 * @property emailText Optional email body.
 */
@ConsistentCopyVisibility
@Serializable
data class ExternalNotification private constructor(
    val channels: Set<ExternalChannel> = setOf(ExternalChannel.SMS, ExternalChannel.EMAIL),
    val smsText: String? = null,
    val emailTitle: String? = null,
    val emailText: String? = null,
) {
    /** Omits free text so it cannot reach a log line accidentally. */
    override fun toString(): String = "ExternalNotification(channels=$channels)"

    companion object {
        /**
         * Enables both carriers; the platform picks the one the person is reachable on.
         *
         * @param smsText Optional SMS body.
         * @param emailTitle Optional email subject.
         * @param emailText Optional email body.
         */
        fun smsAndEmail(
            smsText: String? = null,
            emailTitle: String? = null,
            emailText: String? = null,
        ): ExternalNotification =
            ExternalNotification(
                channels = setOf(ExternalChannel.SMS, ExternalChannel.EMAIL),
                smsText = smsText,
                emailTitle = emailTitle,
                emailText = emailText,
            )

        /**
         * Enables SMS only.
         *
         * @param smsText Optional SMS body.
         */
        fun smsOnly(smsText: String? = null): ExternalNotification =
            ExternalNotification(channels = setOf(ExternalChannel.SMS), smsText = smsText)

        /**
         * Enables email only.
         *
         * @param emailTitle Optional email subject.
         * @param emailText Optional email body.
         */
        fun emailOnly(
            emailTitle: String? = null,
            emailText: String? = null,
        ): ExternalNotification =
            ExternalNotification(
                channels = setOf(ExternalChannel.EMAIL),
                emailTitle = emailTitle,
                emailText = emailText,
            )
    }
}

/** Distribution priority for a document sent through dokumentdistribusjon. */
enum class DistributionType {
    /** Important document. */
    IMPORTANT,

    /** Ordinary document. */
    OTHER,
}

/**
 * A producer-created journalpost that may be sent when external varsling is unavailable.
 *
 * @property journalpostId Identifier for the already-created journalpost.
 * @property distributionType Distribution priority for the fallback document.
 */
@Serializable
data class BrevFallback(
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
) {
    /** Omits [journalpostId] because it identifies a document about a person. */
    override fun toString(): String = "BrevFallback(distributionType=$distributionType)"
}

/** Time window in which Budstikka may dispatch the notification. */
enum class SendingWindow {
    /** Dispatch without an opening-hours restriction. */
    ONGOING,

    /** Dispatch only during Budstikka opening hours. */
    BUDSTIKKA_OPENING_HOURS,
}

/** Producer-selected category; budstikka does not branch on it. */
@InternalBudstikkaWire
enum class Tag { DIALOGMOETE, OPPFOELGING }

@InternalBudstikkaWire
enum class AltinnResourceId { DIALOGMOETE, }

/** Message type for Arbeidsgivervarsel, distinct from [Varseltype]. */
@InternalBudstikkaWire
enum class ArbeidsgiverMeldingstype { BESKJED, OPPGAVE }

/** Producer-owned case identifier used for downstream grouping. */
@InternalBudstikkaWire
@Serializable
data class Sakstilknytning(
    val sakId: String,
) {
    /** Omits [sakId] because a case belongs to a person. */
    override fun toString(): String = "Sakstilknytning()"
}
