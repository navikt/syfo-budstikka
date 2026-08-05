package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.Serializable

enum class Varseltype { BESKJED, OPPGAVE }

sealed interface Brukervarsel {
    val partitionKey: String
}

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
}

enum class ExternalChannel { SMS, EMAIL }

/**
 * Optional plain-text overrides for external varsling. A `null` field leaves that channel's text
 * unspecified.
 */
@Serializable
data class ExternalVarsling(
    val channels: Set<ExternalChannel> = setOf(ExternalChannel.SMS, ExternalChannel.EMAIL),
    val smsText: String? = null,
    val emailTitle: String? = null,
    val emailText: String? = null,
)

enum class DistributionType { IMPORTANT, OTHER }

/** A producer-created journalpost that may be sent when external varsling is unavailable. */
@Serializable
data class BrevFallback(
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
)

enum class SendingWindow { ONGOING, BUDSTIKKA_OPENING_HOURS }

/** Producer-selected category; budstikka does not branch on it. */
enum class Tag { DIALOGMOETE, OPPFOELGING }

enum class AltinnResourceId { DIALOGMOETE }

enum class ArbeidsgiverMeldingstype { BESKJED, OPPGAVE }

/** Producer-owned case identifier used for downstream grouping. */
@Serializable
data class Sakstilknytning(
    val sakId: String,
)
