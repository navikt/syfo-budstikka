package no.nav.budstikka.infrastructure.database.dispatch

/**
 * Livssyklus-tilstandene til en `inbox_message`-rad. Persisteres som TEXT i `state`-kolonnen via
 * [name] (kolonnen holdes TEXT, ikke enum-kolonne, så skjema-drift-sjekken mot Flyway forblir tom).
 *
 * - [RECEIVED]: utgangspunktet, satt av Kafka-konsumet.
 * - [CLAIMED]: grepet av beslutnings-workeren med lease (ADR 0004) — usynlig for andre pollere til
 *   leasen løper ut eller raden effektueres.
 * - [PROCESSED] / [DROPPED] / [FAILED]: terminale utfall fra effektueringen (#56).
 *
 * Delivery-tabellen har en tilsvarende, men egen tilstandsserie (READY/…/SENT). De deler form, ikke
 * verdier, så tilstandene holdes som separate enums per tabell.
 */
enum class InboxMessageState {
    RECEIVED,
    CLAIMED,
    PROCESSED,
    DROPPED,
    FAILED,
}
