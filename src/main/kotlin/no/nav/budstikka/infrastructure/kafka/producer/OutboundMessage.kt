package no.nav.budstikka.infrastructure.kafka.producer

/**
 * En produsert melding uten destinasjon. En [ProducerHandler] eier nøkkel og payload; hvilken topic
 * meldingen havner på er deploy-config og bindes av [publish] – aldri av handleren selv.
 */
data class OutboundMessage(
    val id: String,
    val value: String,
)
