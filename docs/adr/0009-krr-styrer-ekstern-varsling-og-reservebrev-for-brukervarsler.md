# ADR 0009 — KRR styrer ekstern varsling og brev som reservekanal for brukervarsler til sykmeldte

- Status: Besluttet
- Dato: 2026-07-22

For `BrukervarselCreate` til sykmeldte slår budstikka opp digital kontaktstatus
i KRR når produsenten ber om ekstern varsling eller oppgir et brev som
reservekanal. Når `kanVarsles` er `false`, fjernes SMS og e-post, mens
Min side-varselet beholdes. Hvis produsenten har oppgitt et reservebrev,
opprettes også en brevleveranse.

`kanVarsles=false` omfatter både reservasjon og manglende verifisert digital
kontaktkanal. Vi valgte denne bredere semantikken fordi kontroll av bare
reservasjon kan etterlate en person uten verken ekstern varsling eller brev.
Rene Min side-varsler skal ikke utløse KRR-oppslag. Beslutningen gjelder ikke
`LEDERVARSEL` eller `ARBEIDSGIVERVARSEL`.

En sykmeldingsstatus-sjekk for møtebehov er ikke en del av denne felles gaten;
eierskapet til den domeneregelen avklares i issue #167.
