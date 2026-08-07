# Migrering: esyfovarsel → syfo-budstikka

En flyt flyttes som en hel prosess. Systemet som behandler opprettelsen
beholder ansvaret for alle senere oppdateringer og ferdigstillinger. Det
hindrer dobbeltvarsling og at budstikka prøver å lukke en referanse som bare
esyfovarsel kjenner.

## Kildegrunnlag

Fakta om esyfovarsel i dette dokumentet ble kontrollert 2026-08-07 mot
[`navikt/esyfovarsel` commit
`4a29705189f584f5caa9371bc5ae51caffef379e`](https://github.com/navikt/esyfovarsel/commit/4a29705189f584f5caa9371bc5ae51caffef379e).
Observasjonene gjelder denne revisjonen og må kontrolleres på nytt før en
senere cutover.

## Bekreftet i esyfovarsel

- `VarselBusKafkaConsumer` leser `team-esyfo.varselbus` og sender hendelsen til
  `VarselBusService`.
- En hendelse med `ferdigstill=true` går direkte til `SenderFacade`. Andre
  hendelser rutes etter type til en spesifikk service. Hendelser til sykmeldte
  behandles også av microfrontend-flyten.
- Når esyfovarsel oppretter en leveranse, lagrer `SenderFacade` kanal og
  ekstern referanse i `UTSENDT_VARSEL`.
- Ved ferdigstilling finner `SenderFacade` uferdigstilte rader etter sykmeldt,
  virksomhet og hendelsestype. Den bruker deretter den lagrede eksterne
  referansen til å lukke riktig kanal.
- Den felles hendelsesformen har ingen obligatorisk, kanal-uavhengig referanse
  som en ny tjeneste kan bruke til å lukke alle gamle leveranser. Brev inngår
  heller ikke i esyfovarsels ferdigstillingsoperasjon.

Kanalinventar og kanalspesifikke detaljer eies av
[esyfovarsel-kanalkartet](esyfovarsel-kanalkart.md), ikke av denne planen.

## Foreslått cutover

1. Produsenten setter `varselsystem` til `ESYFOVARSEL` eller `BUDSTIKKA` når
   den oppretter en prosess. Flagget lagres hos produsenten, ikke i budstikka.
2. Produsenten ruter alle senere hendelser for prosessen, inkludert
   ferdigstilling, til systemet flagget peker på. En prosess må aldri sendes til
   begge systemene.
3. For en tilstandsmaskin eller en serie med oppfølgingshendelser er
   migreringsenheten prosessen eller grupperingen, ikke én enkelt hendelse.
   Produsenten må sende opprettelsen og senere hendelser for prosessen med samme
   Kafka-partisjonsnøkkel, slik at rekkefølgen bevares.
4. Hver produsent og hver flyt flyttes separat. Før cutover må budstikka støtte
   alle nødvendige kanaler, oppdateringer og ferdigstillinger for flyten.
5. Produsenten beholder esyfovarsel-rutingen for prosesser merket
   `ESYFOVARSEL` til de er ferdige. Den gamle rutingen kan fjernes først når
   produsenten kan dokumentere at ingen slike prosesser er åpne.
6. En rollback påvirker bare nye prosesser. Eksisterende flagg beholdes, slik
   at de fortsatt rutes til systemet som opprettet dem.

## Åpen produktbeslutning: møtebehov

`MotebehovVarselService` sender bare varsel til nærmeste leder når den
sykmeldte har en `SENDT` sykmelding for den aktuelle virksomheten. Dette er en
egen domeneregel og ikke en del av KRR-gaten.

[#167](https://github.com/navikt/syfo-budstikka/issues/167) eier beslutningen
om regelen skal ligge hos produsenten, budstikka eller utgå. Cutover for denne
flyten må ikke anta samme oppførsel før produktansvarlig har valgt og
akseptansekriteriene i saken er oppfylt.
