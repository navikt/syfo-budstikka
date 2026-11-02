package no.nav.budstikka.contract

import kotlinx.serialization.Serializable

/** Budstikka-owned sak for content in Arbeidsgivernotifikasjoner. */
@InternalBudstikkaWire
@Serializable
enum class Sakstype { DIALOGMOTE, OPPFOLGINGSPLAN }

/** Saksstatus a producer asks Budstikka to set, after the content. */
@InternalBudstikkaWire
@Serializable
enum class Saksstatus { MOTTATT, UNDER_BEHANDLING, FERDIG }
