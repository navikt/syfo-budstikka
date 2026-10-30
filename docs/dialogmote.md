# Dialogmøtevarsler gjennom Budstikka

> Overgangsdokument for flyttingen fra esyfovarsel. Slettes når esyfovarsel er slått av.

Guiden viser hvilke Budstikka-kall isdialogmote bruker for dialogmøtehendelsene som i dag går gjennom esyfovarsel (`SM_DIALOGMOTE_*` og `NL_DIALOGMOTE_*`), og hvilke varsler som skal lukkes. Generelle regler står i [sende-varsler.md](sende-varsler.md), og cutover per møte i [migrering.md](migrering.md). Fakta om esyfovarsel er kontrollert mot [`navikt/esyfovarsel` `56ed5eb`](https://github.com/navikt/esyfovarsel/tree/56ed5eb), der tekstene ligger i `VarselTexts.kt`.

## Forutsetninger

- `budstikka-kontrakt` 0.7.0 eller nyere, og skrivetilgang til `team-esyfo.budstikka.v1`.
- `EventId` lagres transaksjonelt med møteendringen og gjenbrukes ved retry.
- Bruk `SendingWindow.ONGOING` der funksjonen har parameteret, så en lukking ikke kan nå fram før en opprettelse som venter på sendevinduet. Unntaket er referatbeskjeden til leder.
- isdialogmote sender bare SM-hendelser når den sykmeldte kan varsles digitalt, og distribuerer brev selv. Bruk derfor ikke `brevFallback`.
- Planlagt skjuling av microfrontenden med `visibleUntil` krever at #282 er rullet ut.

## Referanser

Bruk stabile UUID-er som isdialogmote allerede har, aldri fødselsnummer. isdialogmote må selv huske hvilke referanser som er åpne per møte; esyfovarsel gjør det i dag i tabellen `UTSENDT_VARSEL`.

| Objekt | `reference` | Brukes til |
| --- | --- | --- |
| Brukervarsel | arbeidstakerbrevets uuid | `brukervarselInactivate` |
| Ditt Sykefravær | arbeidstakerbrevets uuid | `dittSykefravaerInactivate` |
| Dine Sykmeldte | arbeidsgiverbrevets uuid | `dineSykmeldteVarselInactivate` |
| Kalenderavtale | arbeidsgiverbrevets uuid for INNKALT, NYTT_TID_STED eller ENDRET | `kalenderavtaleUpdate`; hver tid/sted-versjon er en egen avtale |
| Microfrontend | møtets uuid | Bare korrelasjon; Min side matcher på `syfo-dialog` |

## Sykmeldt

Lenkebase i prod er `https://www.nav.no/syk/dialogmoter`. Ekstern varsling er `ExternalNotification.smsOnly()`. Ditt Sykefravær-meldinger får `visibleUntil` = nå + 4 uker.

| Hendelse | Brukervarsel | Ditt Sykefravær | Microfrontend |
| --- | --- | --- | --- |
| INNKALT | `OPPGAVE` «Du er innkalt til dialogmøte - vi trenger svaret ditt», lenke `{base}/sykmeldt` | Opprett samme tekst. Lukk tidligere INNKALT, NYTT_TID_STED og ENDRET. | `microfrontendEnable("syfo-dialog", visibleUntil = møtetidspunkt)` |
| NYTT_TID_STED | `OPPGAVE` «Dialogmøtet med Nav er flyttet» | Opprett «Du har mottatt et brev om endret dialogmøte». Lukk som over. | Ny enable med nytt `visibleUntil` |
| ENDRET | `OPPGAVE` «Dialogmøtet med Nav er endret» | Som NYTT_TID_STED | Som NYTT_TID_STED |
| AVLYST | `BESKJED` «Dialogmøtet med Nav er avlyst» | Opprett «Du har mottatt et brev om avlyst dialogmøte». Lukk som over. | `microfrontendDisable("syfo-dialog")` |
| REFERAT | `BESKJED` «Du har mottatt et referat fra dialogmøte», lenke `{base}/sykmeldt/referat/{brevUuid}` | Opprett samme tekst. Lukk som over. | `microfrontendDisable("syfo-dialog")` |
| LEST | `brukervarselInactivate` for brevet som er lest | Ingen endring | Ingen endring |

- **Lukking:** før avlysning, nytt tid/sted, endring og referat sender isdialogmote `brukervarselInactivate` for åpne INNKALT-, NYTT_TID_STED- og ENDRET-varsler.
- **`messageType`:** behold esyfovarsels `ESYFOVARSEL_DIALOGMOTE_<TYPE>` (ENDRET blir `ESYFOVARSEL_DIALOGMOTE_ENDRET`), eller avklar nye verdier med Flex.
- **`visibleUntil`:** esyfovarsel skjuler panelet kl. 07 på møtedagen. Møtetidspunktet viser det fram til møtet starter.

## Delt microfrontend

`syfo-dialog` er ett kort som viser dialogmøtepanelet, møtebehovpanelet eller ingenting. Både isdialogmote og syfomotebehov skrur det av og på, og siste kommando vinner. En overflødig enable er ufarlig; en disable mens den andre trenger kortet er det ikke. Derfor:

- **isdialogmote** sender enable ved innkalling, nytt tid/sted og endring, og disable ved avlysning og referat.
- **syfomotebehov** sender enable bare når det ikke finnes et aktivt møte, og **aldri disable mens det finnes et aktivt møte**.

syfomotebehov må flytte før eller samtidig med isdialogmote. Samtidig må esyfovarsel slutte å la `SM_DIALOGMOTE_SVAR_MOTEBEHOV` styre kortet.

## Nærmeste leder

Budstikka slår opp aktiv leder og e-postadresser ved utsending, og eier saken. Arbeidsgivervarsler og kalenderavtaler til leder bruker `saktype = Arbeidsgiversak.Type.DIALOGMOTE`; isdialogmote sender ingen sak. Budstikka legger alt i lederrelasjonens dialogmøtesak, med tittel «Dialogmøte med {navn}» og lenke til Dine Sykmeldte. syfomotebehov bruker samme saktype, så møtebehov havner i samme sak. E-posttekstene er HTML; bruk `Kalenderavtale.EmailNotification.html` og `Arbeidsgivervarsel.HtmlEmailNotification` med statisk markup.

| Hendelse | Kall, i denne rekkefølgen |
| --- | --- |
| INNKALT | `dineSykmeldteVarselCreate(DIALOGMOTE_INNKALLING, "Innkalling til dialogmøte")`, deretter `kalenderavtaleCreate(reference = arbeidsgiverbrevUuid, text = "Dialogmøte med {fornavn}", startAt = møtetidspunkt, email = innkallingstekstene, saksstatus = UNDER_BEHANDLING)` |
| NYTT_TID_STED, ENDRET | `dineSykmeldteVarselCreate(DIALOGMOTE_ENDRING, "Endring av dialogmøte")`, `kalenderavtaleUpdate(forrige referanse, tilstand = AVLYST)` uten e-post, deretter `kalenderavtaleCreate` med ny referanse, nytt tidspunkt, endringstekstene og `saksstatus = UNDER_BEHANDLING` |
| AVLYST | `dineSykmeldteVarselCreate(DIALOGMOTE_AVLYSNING, "Avlysning av dialogmøte")`, deretter `kalenderavtaleUpdate(tilstand = AVLYST, email = avlysningstekstene, saksstatus = FERDIG)` |
| REFERAT | `kalenderavtaleUpdate(tilstand = AVHOLDT)` uten e-post, deretter `arbeidsgivervarselCreate(recipient = NarmesteLeder(sykmeldt), htmlEmail = referattekstene, saktype = DIALOGMOTE, text = "Du har mottatt et referat fra dialogmøte", saksstatus = FERDIG)` |
| SVAR | `kalenderavtaleUpdate` med `ARBEIDSGIVER_HAR_GODTATT` (KOMMER), `ARBEIDSGIVER_VIL_AVLYSE` (KOMMER_IKKE) eller `ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED` (NYTT_TID_STED), og `dineSykmeldteVarselInactivate` for møtets åpne Dine Sykmeldte-referanser |

Ved nytt tidspunkt får lederen én e-post, på den nye avtalen, ikke to som i dag.

**Lederbytte:** nytt innhold havner automatisk i den nye lederens sak. En oppdatering av en eksisterende avtale treffer avtalen i den gamle saken, mens e-post og status går til den nye lederen. Trenger produktet at den nye lederen ser avtalen, avlyser isdialogmote den gamle uten e-post og oppretter en ny.

## Uten nærmeste leder

`AG_VARSEL_ALTINN_RESSURS` sendes i dag av syfo-dokumentporten når isdialogmote mangler nærmeste leder. Dokumentporten skal bruke `arbeidsgivervarselCreate` med `Arbeidsgivervarsel.AltinnResource`, `sykmeldt`, `saktype = DIALOGMOTE` og dialog-id-en sin som `sakId`, så alle varsler om samme ansatte og virksomhet havner i samme sak.

## Endringer fra esyfovarsel

- Saken gjelder lederrelasjonen, ikke ett møte, så statusen gjelder alle lederens dialogmøter og møtebehov for den ansatte.
- esyfovarsel lukker alle åpne Dine Sykmeldte-varsler for leder, ansatt og virksomhet ved svar. isdialogmote lukker bare møtets egne.
- Budstikka prøver sak- og kalenderkall på nytt innenfor attempt-budsjettet; esyfovarsel gjør ikke det.
- esyfovarsel sender ikke noe til Dine Sykmeldte ved referat. `DIALOGMOTE_REFERAT` finnes hvis produktet ønsker det.
