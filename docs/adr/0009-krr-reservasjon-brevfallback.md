# ADR 0009 — KRR styrer ekstern varsling og brev-fallback

- Status: Besluttet
- Dato: 2026-07-22

Budstikka bruker maskin-til-maskin-autentisering for å slå opp digital
kontaktstatus i KRR når et brukervarsel ber om ekstern varsling eller
brev-fallback. Når `kanVarsles` er `false`, fjernes SMS/e-post, mens
in-app-varselet beholdes. Hvis produsenten har oppgitt brev-fallback, opprettes
også en brevleveranse.

`kanVarsles=false` omfatter både reservasjon og manglende verifisert digital
kontaktkanal. Vi valgte denne bredere semantikken fordi kontroll av bare
reservasjon kan etterlate en person uten verken ekstern varsling eller brev.
Rene in-app-varsler og andre kanaler skal ikke utløse KRR-oppslag.

En sykmeldingsstatus-sjekk for møtebehov er ikke en del av denne felles gaten;
eierskapet til den domeneregelen avklares i issue #167.
