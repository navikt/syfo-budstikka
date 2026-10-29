# ADR 0019 — Sak og kalenderavtale er generiske operasjoner i Budstikka

- Status: Besluttet
- Dato: 2026-10-29

Alt innhold i Min side – arbeidsgiver hører til en sak. Fager håndhever ikke det fullt ut ennå, men har varslet at det kommer. Budstikka behandler derfor saken som en del av hver operasjon, ikke som en egen ting produsenten må opprette først. Produsenten beskriver saken (`Arbeidsgiversak`: `sakId`, tittel, lenke og slettefrist) på arbeidsgivervarsler, kalenderavtaler og statusendringer. Budstikka sørger for at saken finnes i samme leveranse som innholdet. Kalenderavtale er likevel en egen operasjon, fordi den har eget livsløp med tilstand og oppdateringer.

Produsenten eier identiteten og livsløpet: den velger `sakId` og kalenderavtalens `reference` (eksternId), og bestemmer hver statusendring. Budstikka bruker Fagers nøkkelbaserte mutasjoner og lagrer verken Fager-id-er eller sakstilstand.

**Sak per lederrelasjon.** En sak er låst til mottakeren den ble opprettet for, og Fager avviser innhold til en annen mottaker. For nærmeste leder er Fagers grupperingsid derfor `sakId` pluss lederrelasjonens id fra esyfo-narmesteleder, slått opp ved utsending. En ny leder får automatisk sin egen sak for samme `sakId`, og forrige leder mister tilgangen gjennom Fagers egen tilgangsstyring. For en Altinn-ressurs er grupperingsid lik `sakId`. Slik oppfører Budstikka seg som esyfovarsels «pågående sak per nærmeste leder», men uten tabell og uten fødselsnummer.

**Ingen tilstand speiles.** esyfovarsel lagret sak og kalenderavtaler i egne tabeller, fordi hendelsene ikke hadde produsentens identitet. Kopien kom ut av synk ved delvise feil og lederbytte. Her er produsentens id-er nøklene, og Fager er fasit. Budstikka gjør operasjonen og bruker Fagers svar:
- et identisk gjentak gir suksess fra Fager selv;
- `SakFinnesIkke` og `NotifikasjonFinnesIkke` betyr «ikke ennå» og prøves på nytt;
- `Duplikat*` betyr at nøkkelen finnes med annet innhold, og da slår Budstikka opp det eksisterende objektet. En sak i samme virksomhet eller en avtale i samme sak er vår egen; alt annet er en nøkkelkollisjon og feiler.

Fagers spørringer brukes bare slik, ved konflikt, og ellers til diagnose. Fagers produsentmodell kan henge etter, så et oppslag rett etter en skriving kan være utdatert.

Vi valgte dette framfor at produsenten kaller Fager direkte, som #251 la til grunn. Med to uavhengige lederoppslag, ett hos produsenten for saken og ett i Budstikka for innholdet, kan svarene bli ulike ved lederbytte. Budstikka er dessuten allerede registrert hos Fager for «Dialogmøte», og har e-postoppslag, retry og observability. isdialogmote er ikke registrert.

Vi valgte også bort en egen operasjon for å opprette sak. Da må saken nå fram før innholdet, og en sak og en avtale som sendes rett etter hverandre, kan komme i vilkårlig rekkefølge og gi forsinkelser.

Prisen:
- **Kontrakten får Fager-begreper.** Saksstatus, kalendertilstand og operasjonen `UPDATE` er kanalgeneriske, ikke dialogmøtespesifikke.
- **Ett ekstra kall.** Hver leveranse med sak gjør et `nySak`-kall før innholdet.
- **Tittel og slettefrist settes når saken opprettes.** Slettefristen kan forlenges med en statusendring.
- **Ulike nøkler.** For nærmeste leder er Fagers grupperingsid ikke lik produsentens `sakId`.
- **Oppdateringer følger avtalen.** En kalenderoppdatering treffer avtalen der den ble opprettet. Etter et lederbytte havner nye avtaler i den nye lederens sak.

Kalender-e-post sendes i Fagers NKS-vindu og ikke etter Budstikkas sendevindu. Avtalen må finnes med en gang, slik at senere oppdateringer kan adressere den, og Fager stopper ikke-sendt e-post når avtalen avlyses. Fager dedupliserer ikke oppdateringer av kalenderavtaler (argumentet `idempotencyKey` leses ikke). Et retry etter en vellykket oppdatering med e-post kan derfor gi dobbel e-post.
