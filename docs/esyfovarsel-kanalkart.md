# esyfovarsel: kanalkart og dokdist-detaljer

Kildekode-kartlegging av `navikt/esyfovarsel` (HEAD per 2026-08-05). Supplerer
[migrering.md](migrering.md) med kanalinventar, varseltype-ruting og — viktigst —
dokumentdistribusjons-detaljene som styrte `brevCreate`-designet i
[produsentveiledningen](sende-varsler.md). Flyten i esyfovarsel: Kafka-consumer på
`team-esyfo.varselbus` → `VarselBusService` → per-type service → `SenderFacade` →
kanaladapter.

## Kanalinventar

`Kanal`-enumen i esyfovarsel: `DINE_SYKMELDTE`, `DITT_SYKEFRAVAER`,
`ARBEIDSGIVERNOTIFIKASJON`, `BRUKERNOTIFIKASJON`, `BREV`. Matcher budstikkas
variantsett én-til-én, med ett unntak: microfrontend står *utenfor* enumen i
esyfovarsel (egen sidebane utenom `SenderFacade`, aldri logget i `UTSENDT_VARSEL`,
egen tabell `MIKROFRONTEND_SYNLIGHET` og egen opprydningsjobb). Budstikka har
microfrontend som førsteklasses variant og har dermed allerede tettet den asymmetrien.

| Kanal (esyfovarsel) | Nedstrøms | Budstikka-variant |
|---|---|---|
| `BRUKERNOTIFIKASJON` | Kafka `min-side.aapen-brukervarsel-v1` (tms varsel-builder) | `BrukervarselCreate` |
| `DINE_SYKMELDTE` | Kafka `team-esyfo.dinesykmeldte-hendelser-v2` | `LedervarselCreate` (fasade: `dineSykmeldteVarselCreate`) |
| `DITT_SYKEFRAVAER` | Kafka `flex.ditt-sykefravaer-melding` | `DittSykefravaerCreate` (ikke sendbar ennå) |
| `ARBEIDSGIVERNOTIFIKASJON` | GraphQL mot `notifikasjon-produsent-api` (fager) | `ArbeidsgivervarselCreate` (fasade: `arbeidsgivervarselCreate`) |
| `BREV` | REST `dokdistfordeling` `/rest/v1/distribuerjournalpost` | `BrevCreate` |
| (microfrontend, utenfor `Kanal`) | Kafka `min-side.aapen-microfrontend-v1` | `MicrofrontendEnable`/`-Disable` |

Merk om `ARBEIDSGIVERNOTIFIKASJON`: esyfovarsel bruker fire operasjonstyper mot
notifikasjon-produsent-api — beskjed/oppgave (med NL-mottaker *eller*
Altinn-ressurs-mottaker), **sak** (`nySak`, `nyStatusSakByGrupperingsid`) og
**kalenderavtale** (opprett/oppdater, med leders e-post). Sak og kalenderavtale er
stateful objekter, ikke engangsvarsler; kontraktens dekning av disse er en egen
beslutning.

Budstikka slår opp aktiv nærmeste leder ved utsending. Når produsenten har bedt om
ekstern varsling og den aktive lederen mangler e-postadresse, feiler hele leveransen
terminalt. Den degraderes ikke til bare in-app-varsel.

## Dokumentdistribusjon: kallsteder og tvingKanal

Ett HTTP-kallsted (`JournalpostdistribusjonConsumer`), med `tvingKanal = PRINT` kun
når `tvingSentralPrint = true`. `SenderFacade` har to metoder som bare skiller seg i
det flagget — og navnene lyver: `sendBrevTilFysiskPrint` tvinger *ikke* print
(`tvingSentralPrint = false`, dokdist velger kanal; ikke-reserverte får Digipost).

| Kallsted | tvingKanal | Trigger |
|---|---|---|
| `AktivitetspliktForhandsvarselService` | ordinær | KRR-fallback (`canUserBeDigitallyNotified == false`) |
| `MerVeiledningVarselService` | ordinær | KRR-fallback |
| `DialogmoteInnkallingSykmeldtVarselService` | ordinær (type `ANNET`) | KRR-fallback |
| `ArbeidsuforhetForhandsvarselService` | ordinær | **Primærkanal, ingen KRR-sjekk** |
| `ManglendeMedvirkningVarselService` | ordinær | **Primærkanal, ingen KRR-sjekk** |
| `FriskmeldingTilArbeidsformidlingVedtakService` | ordinær | **Primærkanal, ingen KRR-sjekk** |
| `ResendFailedVarslerJob` | ordinær | Retry av feilede BREV-utsendinger |
| `SendAktivitetspliktLetterToSentralPrintJob` | **PRINT** | Renotifisering: ulest aktivitetsplikt-brukervarsel, 1–14 dager, har journalpostId |

Altså: 7 av 8 ordinær løype; tre varseltyper har brev som eneste kanal uten
KRR-sjekk (ikke-reserverte får disse digitalt i Digipost i dag); kun
aktivitetsplikt-renotifiseringen tvinger papir. Dette er grunnlaget for at
`BrevCreate.tvingSentralPrint` defaulter til `false`.

`DistibusjonsType` (sic) i esyfovarsel: `VIKTIG`, `ANNET`, `VEDTAK` — `VEDTAK` er
aldri brukt. Responshåndtering: `200` og `409 Conflict` regnes som suksess; `410 Gone`
(død mottaker, ukjent adresse) markerer varselet `resend_exhausted`.

## KRR/reservasjon: kanVarsles, ikke reservert

esyfovarsels gate er `kanVarsles` fra digdir-krr-proxy — bredere enn `reservert`:
dekker også manglende verifisert kontaktinfo siste 18 mnd. Feltet `reservert`
deserialiseres, men leses aldri. Feilhåndteringen er *fail-open-til-brev*: DKIF-nede,
401 eller ukjent person gir `kanVarsles = null` → tolkes som «kan ikke varsles
digitalt» → brev-grenen.

Budstikkas `KrrClient` bruker samme semantikk (`!person.kanVarsles`), men feiler
*lukket*: KRR-feil kaster og gir retry i stedet for stille papirutsending. Det er en
bevisst forskjell — esyfovarsel kunne sende papirbrev på en DKIF-blipp. Merk at
navnene `ReservationGate`/`isReserved` i budstikka er trangere enn semantikken de
faktisk har.

Per-kanal-håndtering for personer med `kanVarsles = false` i esyfovarsel:

- `BRUKERNOTIFIKASJON`, to mønstre: (a) undertrykk helt og send brev i stedet
  (aktivitetsplikt, mer veiledning, dialogmøte-SM); (b) send on-page-varselet, men
  dropp SMS/e-post (`eksternVarsling = false`) (møtebehov, oppfølgingsplan,
  kartlegging).
- `DITT_SYKEFRAVAER` og microfrontend: sjekkes aldri, sendes ubetinget — også i
  brev-grenene.
- `DINE_SYKMELDTE`/`ARBEIDSGIVERNOTIFIKASJON`: ikke relevant (mottaker er
  leder/virksomhet; leders e-post hentes fra `narmesteleder`-API-et).
- Dokdist vurderer reservasjon uavhengig etterpå — reservasjon evalueres altså to
  steder med ulike innganger.

## Varseltype → kanal (fanout)

`HendelseType` har 25 verdier; prefiks `SM_` = sykmeldt, `NL_` = nærmeste leder,
`AG_` = virksomhet. Ruting i `VarselBusService.processVarselHendelse`. Utvalg som
viser mønstrene:

| HendelseType | Kanaler |
|---|---|
| `SM_DIALOGMOTE_INNKALT` | Brukervarsel (OPPGAVE) *eller* brev (KRR-fallback) + Ditt sykefravær (alltid) + microfrontend |
| `NL_DIALOGMOTE_INNKALT` | Dine sykmeldte + AG-sak (`nySak`) + AG-kalenderavtale (med leder-e-post) |
| `SM_MER_VEILEDNING` | Brukervarsel *eller* brev + Ditt sykefravær (alltid) + microfrontend |
| `SM_AKTIVITETSPLIKT` | Brukervarsel *eller* brev + microfrontend; + tvungen print-jobb etter 1–14 dager ulest |
| `SM_ARBEIDSUFORHET_FORHANDSVARSEL` | **Kun brev** (ordinær løype, ingen KRR-sjekk) |
| `SM_FORHANDSVARSEL_MANGLENDE_MEDVIRKNING` | **Kun brev** (ordinær løype, ingen KRR-sjekk) |
| `SM_VEDTAK_FRISKMELDING_TIL_ARBEIDSFORMIDLING` | **Kun brev** (ordinær løype, ingen KRR-sjekk) |
| `AG_VARSEL_ALTINN_RESSURS` | AG-notifikasjon (Altinn-ressurs) + sak |

`DineSykmeldteHendelseType` i esyfovarsel mapper
[`NL_OPPFOLGINGSPLAN_VARSELBESTILLING` til wire-verdien `OPPFOLGINGSPLAN_PAAMINNELSE`](https://github.com/navikt/esyfovarsel/blob/4a29705189f584f5caa9371bc5ae51caffef379e/src/main/kotlin/no/nav/syfo/kafka/consumers/varselbus/domain/DineSykmeldteHendelseType.kt#L10-L15).

Ortogonalt: `ferdigstill == true` kortslutter til per-kanal-inaktivering
(brukervarsel `inaktiver`, Ditt sykefravær `LukkMelding`, Dine sykmeldte
`FerdigstillHendelse`, AG-notifikasjon `hardDelete`). `BREV` har ingen
ferdigstilling — brev kan ikke kalles tilbake.
