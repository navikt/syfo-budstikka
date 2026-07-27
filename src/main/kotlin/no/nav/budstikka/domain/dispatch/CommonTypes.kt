package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.Serializable

/** Brukervarsel type on Min side (B40). tms also supports `Innboks`, but it is never used and is omitted. */
enum class Varseltype { BESKJED, OPPGAVE }

sealed interface Brukervarsel {
    val partitionKey: String
}

/** External notification channel (SMS/email) in addition to the surface. */
enum class ExternalChannel { SMS, EMAIL }

/**
 * Our model for external notifications (B23), mapped internally to tms. `null` text means the
 * downstream NAV standard text. The consumer supplies plain text; budstikka sanitises it (B29).
 */
@Serializable
data class ExternalVarsling(
    val channels: Set<ExternalChannel> = setOf(ExternalChannel.SMS, ExternalChannel.EMAIL),
    val smsText: String? = null,
    val emailTitle: String? = null,
    val emailText: String? = null,
)

/** Distribution type for downstream letter sending. */
enum class DistributionType { IMPORTANT, OTHER }

/**
 * B8: presence means send a letter when the recipient is reserved against digital contact.
 * `journalpostId` has already been created by the consumer.
 */
@Serializable
data class BrevFallback(
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
)

/**
 * Sending window (B25): neutral term, operationalised by the outbox. Budstikka sets the default
 * (`NKS_AAPNINGSTID` for external-notification-bearing messages, `ONGOING` otherwise). Extensible.
 */
enum class SendingWindow { ONGOING, NKS_OPENING_HOURS }

/**
 * Tag (B30): typed CLOSED enum (category, not behaviour). Budstikka never branches on it; it is
 * carried only to the producer API. The closed form keeps team registration and budstikka onboarding
 * in sync. Extend it during onboarding.
 */
enum class Tag { DIALOGMOETE, OPPFOELGING }

/** B32: Altinn resource → producer API resource ID (registry-enforced). */
enum class AltinnResourceId { DIALOGMOETE, }

/** B33: neutral employer message type, separate from Brukervarsel [Varseltype]. */
enum class ArbeidsgiverMeldingstype { BESKJED, OPPGAVE }

/** B31: the consumer owns the case; `sakId` → downstream grouping ID. */
@Serializable
data class Sakstilknytning(
    val sakId: String,
)
