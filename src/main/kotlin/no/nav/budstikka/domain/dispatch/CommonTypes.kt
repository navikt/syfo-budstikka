package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.Serializable

/** Brukervarsel type on Min side (B40). tms also supports `Innboks`, but it is never used and is omitted. */
enum class Varseltype { BESKJED, OPPGAVE }

sealed interface Brukervarsel {
    val partitionKey: String
}

/** Marker interface for the LEDERVARSEL channel content (mirrors [Brukervarsel]); dispatch seam in the handler. */
sealed interface Ledervarsel {
    val partitionKey: String
}

/**
 * Oppgavetype for LEDERVARSEL (ADR 0015): a CLOSED, budstikka-owned enum (same pattern as
 * [Tag]/[AltinnResourceId]), NOT an opaque string. Required by the dinesykmeldte contract
 * (`OpprettHendelse.oppgavetype`, part of the consumer's PK `(id, oppgavetype)` + UI grouping).
 * Budstikka NEVER branches on the value (`when(oppgavetype)` does not exist, B30/B39) — the enum is a
 * name list. The Kotlin identifier is decoupled from the wire via [wireValue]: dinesykmeldte can
 * change a wire string without touching the producers. #108 ships ONE representative value; the rest
 * are added additively at onboarding (B36).
 */
enum class Oppgavetype(
    val wireValue: String,
) {
    DIALOGMOTE_INNKALLING("DIALOGMOTE_INNKALLING"),
}

/** Channel for `ekstern varsling` (SMS/email) in addition to the surface. */
enum class ExternalChannel { SMS, EMAIL }

/**
 * Model for `ekstern varsling` (B23), currently mapped to TMS for Brukervarsel. A `null` text omits
 * that override. The Produsent supplies plain text; values are currently forwarded unchanged, and
 * the sanitisation required by B29 is not implemented yet.
 */
@Serializable
data class ExternalVarsling(
    val channels: Set<ExternalChannel> = setOf(ExternalChannel.SMS, ExternalChannel.EMAIL),
    val smsText: String? = null,
    val emailTitle: String? = null,
    val emailText: String? = null,
)

/** Distribution type for downstream Brev sending. */
enum class DistributionType { IMPORTANT, OTHER }

/**
 * B8: presence permits a Brev when the Sykmeldt has Reservasjon. Without BrevFallback, Reservasjon
 * only suppresses `ekstern varsling`. The Produsent has already created `journalpostId`.
 */
@Serializable
data class BrevFallback(
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
)

/**
 * Sending-window contract value from B25. Relevant CREATE variants currently carry it unchanged;
 * outbox enforcement and default selection are not implemented yet.
 */
enum class SendingWindow { ONGOING, BUDSTIKKA_OPENING_HOURS }

/**
 * Tag (B30): typed CLOSED enum (category, not behaviour). The current slice preserves it in
 * Arbeidsgivervarsel content but does not map that Channel downstream yet. Extend the enum during
 * onboarding.
 */
enum class Tag { DIALOGMOETE, OPPFOELGING }

/**
 * B32: Altinn resource identifier in the Arbeidsgivervarsel contract; downstream mapping is not
 * implemented yet.
 */
enum class AltinnResourceId { DIALOGMOETE, }

/** B33: neutral Arbeidsgivervarsel message type, separate from Brukervarsel [Varseltype]. */
enum class ArbeidsgiverMeldingstype { BESKJED, OPPGAVE }

/** B31: Produsent-owned case association; `sakId` is intended as the downstream grouping ID. */
@Serializable
data class Sakstilknytning(
    val sakId: String,
)
