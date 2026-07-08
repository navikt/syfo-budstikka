package no.nav.budstikka.domain.formidling

/**
 * Kafka-header-navn som er del av den publiserte kontrakten (delt kilde for produsenter og
 * konsument). Selve header-håndteringen (lesing/validering ved inntak) hører til konsumenten
 * (#19); her defineres kun navnet så begge sider refererer én streng.
 */
object FormidlingHeader {
    /**
     * `eventId` speilet som Kafka-header (samme verdi som [Formidling.eventId] i payloaden).
     * Lar konsumenten dedup-e og lagre rå payload i innboks uten å deserialisere bodyen.
     * Payloaden forblir autoritativ kilde; headeren er en fast-path, ikke en erstatning.
     */
    const val EVENT_ID = "eventId"
}
