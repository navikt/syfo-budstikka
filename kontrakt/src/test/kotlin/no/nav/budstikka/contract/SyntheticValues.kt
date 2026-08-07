package no.nav.budstikka.contract

/**
 * Obviously synthetic values, so a leak assertion fails loudly instead of matching something that
 * only looks harmless. None of these are, or resemble, a real personident, orgnummer or notification.
 */
val SYNTHETIC_SYKMELDT = PersonIdentifier("11111111111")
val SYNTHETIC_ORGNUMMER = Orgnummer("999999999")

const val SYNTHETIC_TEXT = "SYNTETISK-VARSELTEKST"
const val SYNTHETIC_SMS_TEXT = "SYNTETISK-SMSTEKST"
const val SYNTHETIC_EMAIL_TITLE = "SYNTETISK-EPOSTTITTEL"
const val SYNTHETIC_EMAIL_TEXT = "SYNTETISK-EPOSTTEKST"
const val SYNTHETIC_REFERENCE = "SYNTETISK-REFERANSE"
const val SYNTHETIC_JOURNALPOST_ID = "SYNTETISK-JOURNALPOSTID"
const val SYNTHETIC_SAK_ID = "SYNTETISK-SAKID"
const val SYNTHETIC_MICROFRONTEND_ID = "SYNTETISK-MIKROFRONTENDID"
const val SYNTHETIC_LINK = "https://example.invalid/SYNTETISK-LENKE"
