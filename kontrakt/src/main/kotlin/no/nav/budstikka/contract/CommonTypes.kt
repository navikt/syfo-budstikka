package no.nav.budstikka.contract

import kotlinx.serialization.Serializable

enum class Varseltype { BESKJED, OPPGAVE }

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
 */
enum class Oppgavetype(
    val wireValue: String,
) {
    DIALOGMOTE_INNKALLING("DIALOGMOTE_INNKALLING"),

    /**
     * Wire value evidenced by esyfovarsel's `DineSykmeldteHendelseType`, which maps
     * `NL_OPPFOLGINGSPLAN_VARSELBESTILLING` to this same value (source, not an ownership claim):
     * https://github.com/navikt/esyfovarsel/blob/4a29705189f584f5caa9371bc5ae51caffef379e/src/main/kotlin/no/nav/syfo/kafka/consumers/varselbus/domain/DineSykmeldteHendelseType.kt#L10-L15
     */
    OPPFOLGINGSPLAN_PAAMINNELSE("OPPFOLGINGSPLAN_PAAMINNELSE"),
}

enum class ExternalChannel { SMS, EMAIL }

/**
 * Optional plain-text overrides for external notification. A `null` field leaves that channel's text
 * unspecified. Producers use the named factories; decoding remains tolerant of any channel set for
 * wire compatibility.
 *
 * The Kotlin type name is [ExternalNotification]; the serialized field on the wire is
 * `externalVarsling` (unaffected by this class having no discriminator) and is not renamed here — see
 * [BrukervarselCreate.externalVarsling] and [ArbeidsgivervarselCreate.externalVarsling].
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
        /** Both carriers; the platform picks the one the person is reachable on. */
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

        /** SMS only. */
        fun smsOnly(smsText: String? = null): ExternalNotification =
            ExternalNotification(channels = setOf(ExternalChannel.SMS), smsText = smsText)

        /** Email only. */
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

enum class DistributionType { IMPORTANT, OTHER }

/** A producer-created journalpost that may be sent when external varsling is unavailable. */
@Serializable
data class BrevFallback(
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
) {
    /** Omits [journalpostId] because it identifies a document about a person. */
    override fun toString(): String = "BrevFallback(distributionType=$distributionType)"
}

enum class SendingWindow { ONGOING, BUDSTIKKA_OPENING_HOURS }

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
