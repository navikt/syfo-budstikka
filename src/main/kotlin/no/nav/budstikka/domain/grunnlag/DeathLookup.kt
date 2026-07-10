package no.nav.budstikka.domain.grunnlag

import no.nav.budstikka.domain.formidling.Personident

/**
 * Port (B28) for død-oppslag mot folkeregisteret (PDL) – sømmen beslutnings-workeren (#20)
 * bruker for sin død-gate: en person registrert som død skal ikke få formidling
 * (`MOTTATT ──(død via PDL)──▶ DROPPET`, `drop_reason=DEAD`, jf. `docs/DATAMODELL.md`).
 *
 * Grensesnittet er domeneblindt og I/O-fritt i signaturen (`suspend` for å tillate nett i
 * adapteren): workeren avhenger av denne porten, ikke av en konkret PDL-klient. Ekte
 * HTTP/GraphQL-adapter (TokenX/Azure AD M2M mot pdl-api) er egen jobb; i test byttes den mot
 * en in-memory fake (B52).
 */
fun interface DeathLookup {
    /**
     * @return `true` hvis [ident] er registrert som død, ellers `false`.
     */
    suspend fun isDead(ident: Personident): Boolean
}
