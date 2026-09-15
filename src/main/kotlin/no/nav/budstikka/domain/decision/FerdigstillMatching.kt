package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.DittSykefravaerInactivate
import no.nav.budstikka.contract.LedervarselCreate
import no.nav.budstikka.contract.LedervarselInactivate

/** Immutable key for finding the CREATE delivery or a sending-window-held CREATE inbox row. */
data class FerdigstillMatch(
    val reference: String,
    val channel: Channel,
    val recipient: Recipient,
)

internal fun DispatchContent.isFerdigstill(): Boolean =
    this is BrukervarselInactivate ||
        this is LedervarselInactivate ||
        this is ArbeidsgivervarselInactivate ||
        this is DittSykefravaerInactivate

/**
 * Only channels with a runtime adapter can materialize a generic FERDIGSTILL delivery. Ditt
 * Sykefravær remains intentionally unsupported until its downstream contract is approved.
 */
internal fun DispatchContent.toFerdigstillMatch(reference: String): FerdigstillMatch? =
    when (this) {
        is BrukervarselInactivate ->
            FerdigstillMatch(reference, Channel.BRUKERVARSEL, Recipient.Person(sykmeldt))

        is LedervarselInactivate ->
            FerdigstillMatch(reference, Channel.LEDERVARSEL, Recipient.Person(sykmeldt))

        is ArbeidsgivervarselInactivate ->
            FerdigstillMatch(reference, Channel.ARBEIDSGIVERVARSEL, Recipient.Virksomhet(orgnummer))

        else -> null
    }

internal fun DispatchContent.matchesCreate(match: FerdigstillMatch): Boolean =
    when (this) {
        is BrukervarselCreate ->
            match.channel == Channel.BRUKERVARSEL && match.recipient == Recipient.Person(personIdentifier)

        is LedervarselCreate ->
            match.channel == Channel.LEDERVARSEL && match.recipient == Recipient.Person(sykmeldt)

        is ArbeidsgivervarselCreate ->
            match.channel == Channel.ARBEIDSGIVERVARSEL && match.recipient == Recipient.Virksomhet(orgnummer)

        else -> false
    }
