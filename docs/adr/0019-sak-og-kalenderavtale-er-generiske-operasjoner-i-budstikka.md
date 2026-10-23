# ADR 0019 — Sak og kalenderavtale er generiske operasjoner i Budstikka

- Status: Besluttet
- Dato: 2026-10-21

Budstikka tilbyr egne kontraktoperasjoner for Fager-sak og kalenderavtale: opprett sak, sett saksstatus, opprett kalenderavtale og oppdater kalenderavtale. Produsenten eier identiteten og livsløpet. Den velger grupperingsid og eksternId og bestemmer hver overgang. Budstikka utfører operasjonene med Fagers nøkkelbaserte mutasjoner, slår opp nærmeste leder ved utsending og lagrer verken Fager-id-er eller sakstilstand.

Vi valgte dette framfor at produsenten kaller Fager direkte, som #251 la til grunn. Grunnen er at Fager avviser innhold med `UgyldigMottaker` når mottakeren ikke står på saken. To uavhengige lederoppslag, ett hos produsenten for saken og ett i Budstikka for varselet, kan gi forskjellig svar ved lederbytte. Budstikka er dessuten allerede registrert hos Fager for «Dialogmøte», og har e-postoppslag, retry og observability. isdialogmote er ikke registrert.

Prisen er at kontrakten får Fager-begreper (saksstatus og kalendertilstand) og operasjonen `UPDATE`. Fager-begrepene er kanalgeneriske, ikke dialogmøtespesifikke. Rekkefølge mellom sak og innhold løses ved at `SakFinnesIkke` og `NotifikasjonFinnesIkke` prøves på nytt innenfor attempt-budsjettet. En leder som ikke står på saken, gir terminal feil for kalenderavtaler. Beskjed og oppgave sendes da på nytt uten saksgruppering.

Kalender-e-post sendes i Fagers NKS-vindu og ikke etter Budstikkas sendevindu. Avtalen må finnes med en gang, slik at senere oppdateringer kan adressere den, og Fager stopper ikke-sendt e-post når avtalen avlyses. Fager dedupliserer ikke oppdateringer av kalenderavtaler (argumentet `idempotencyKey` leses ikke). Et retry etter en vellykket oppdatering med e-post kan derfor gi dobbel e-post.
