package no.nav.produsenteksempel

import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.AltinnResourceId
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.contract.BrevCreate
import no.nav.budstikka.contract.Brukervarsel
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.DittSykefravaerCreate
import no.nav.budstikka.contract.DittSykefravaerInactivate
import no.nav.budstikka.contract.Ledervarsel
import no.nav.budstikka.contract.LedervarselCreate
import no.nav.budstikka.contract.LedervarselInactivate
import no.nav.budstikka.contract.Microfrontend
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.Sakstilknytning
import no.nav.budstikka.contract.Tag

// One declaration per line makes the negative compiler check fail if any individual raw type loses
// its opt-in marker. Grouped constructor fixtures cannot prove that: another gated type on the same
// line or in the same file could still make compilation fail.
typealias RawAltinnResource = AltinnResource // opt-in-probe
typealias RawAltinnResourceId = AltinnResourceId // opt-in-probe
typealias RawArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype // opt-in-probe
typealias RawArbeidsgiverRecipient = ArbeidsgiverRecipient // opt-in-probe
typealias RawArbeidsgivervarselCreate = ArbeidsgivervarselCreate // opt-in-probe
typealias RawArbeidsgivervarselInactivate = ArbeidsgivervarselInactivate // opt-in-probe
typealias RawBrevCreate = BrevCreate // opt-in-probe
typealias RawBrukervarsel = Brukervarsel // opt-in-probe
typealias RawBrukervarselCreate = BrukervarselCreate // opt-in-probe
typealias RawBrukervarselInactivate = BrukervarselInactivate // opt-in-probe
typealias RawDispatch = Dispatch // opt-in-probe
typealias RawDispatchContent = DispatchContent // opt-in-probe
typealias RawDittSykefravaerCreate = DittSykefravaerCreate // opt-in-probe
typealias RawDittSykefravaerInactivate = DittSykefravaerInactivate // opt-in-probe
typealias RawLedervarsel = Ledervarsel // opt-in-probe
typealias RawLedervarselCreate = LedervarselCreate // opt-in-probe
typealias RawLedervarselInactivate = LedervarselInactivate // opt-in-probe
typealias RawMicrofrontend = Microfrontend // opt-in-probe
typealias RawMicrofrontendDisable = MicrofrontendDisable // opt-in-probe
typealias RawMicrofrontendEnable = MicrofrontendEnable // opt-in-probe
typealias RawNarmesteLeder = NarmesteLeder // opt-in-probe
typealias RawSakstilknytning = Sakstilknytning // opt-in-probe
typealias RawTag = Tag // opt-in-probe
