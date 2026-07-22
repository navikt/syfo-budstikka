package no.nav.budstikka.domain.foundation

import no.nav.budstikka.domain.dispatch.PersonIdentifier

/**
 * Port (B28) for reservasjons-/kontaktstatus mot Kontakt- og reservasjonsregisteret (KRR,
 * digdir-krr-proxy) – sømmen [no.nav.budstikka.domain.decision.ReservationGate] bruker for å
 * avgjøre om ekstern varsling (SMS/e-post) skal undertrykkes og [brevFallback] brukes (B7/B8).
 *
 * «Reservert» er budstikkas NØYTRALE begrep (B23) og betyr her «kan ikke varsles digitalt»: KRR
 * `kanVarsles == false`, som dekker BÅDE reservert-mot-digital-kontakt OG mangler-verifisert-
 * kontaktkanal (ADR 0009). Begge betyr det samme for oss – digital ekstern varsling når ikke fram,
 * så send brev i stedet.
 *
 * Grensesnittet er domeneblindt og I/O-fritt i signaturen (`suspend` for å tillate nett i
 * adapteren): gaten avhenger av denne porten, ikke av en konkret KRR-klient. Ekte HTTP-adapter
 * (Azure AD M2M mot digdir-krr-proxy) er egen jobb; i test byttes den mot en in-memory fake (B52).
 *
 * [brevFallback]: no.nav.budstikka.domain.dispatch.BrevFallback
 */
fun interface ReservationLookup {
    /**
     * @return `true` hvis [ident] ikke kan varsles digitalt (skal få brev i stedet), ellers `false`.
     */
    suspend fun isReserved(ident: PersonIdentifier): Boolean
}
