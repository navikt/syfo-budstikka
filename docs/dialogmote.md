# Dialogmøtevarsler gjennom Budstikka

Denne guiden viser hvordan isdialogmote sender dialogmøtevarslene som i dag går gjennom esyfovarsel (`SM_DIALOGMOTE_*` og `NL_DIALOGMOTE_*`). For hver hendelse står det hvilke Budstikka-kall som gir samme effekt, og hvilke tidligere varsler som skal lukkes. Generelle regler for fasade, `EventId` og retry står i [sende-varsler.md](sende-varsler.md). Cutover per møte står i [migrering.md](migrering.md).

Fakta om esyfovarsel er kontrollert mot [`navikt/esyfovarsel` `56ed5eb`](https://github.com/navikt/esyfovarsel/tree/56ed5eb). Tekstene ligger i `VarselTexts.kt`, og e-posthilsenen bygges i `VarselFunctions.kt`. Budstikka er domeneblind: tekst, lenker og valg av kanal eier produsenten.

## Forutsetninger

- `budstikka-kontrakt` 0.7.0 eller nyere, og skrivetilgang til `team-esyfo.budstikka.v1`.
- `EventId` lagres transaksjonelt med møteendringen før sending. Ved retry gjenbrukes samme id.
- Alle kall bruker `SendingWindow.ONGOING` der funksjonen har parameteret. En lukking skal ikke kunne nå fram før en opprettelse som venter på sendevinduet. Unntaket er referatbeskjeden til leder (se under).
- Microfrontenden `syfo-dialog` deles med syfomotebehov. Se [Delt microfrontend](#delt-microfrontend). Planlagt skjuling med `visibleUntil` krever at #282 er rullet ut.
- isdialogmote sender bare SM-hendelser når den sykmeldte kan varsles digitalt, og distribuerer brev selv. Bruk derfor ikke `brevFallback`. Budstikka slår uansett opp KRR når ekstern varsling er bedt om (ADR 0009).

## Referanser

Bruk stabile UUID-er som isdialogmote allerede har. Bruk aldri fødselsnummer.

| Objekt | `reference` / id | Brukes til |
| --- | --- | --- |
| Brukervarsel | arbeidstakerbrevets uuid | `brukervarselInactivate`. Er også varselId på Min side. |
| Ditt Sykefravær | arbeidstakerbrevets uuid | `dittSykefravaerInactivate` |
| Dine Sykmeldte | arbeidsgiverbrevets uuid | `dineSykmeldteVarselInactivate` |
| Sak | møtets uuid som `sakId` i `Arbeidsgiversak` | Alle kalender-, status- og referatkall til leder for møtet |
| Kalenderavtale | arbeidsgiverbrevets uuid for INNKALT, NYTT_TID_STED eller ENDRET | `kalenderavtaleUpdate`. Hver tid/sted-versjon er en egen avtale. |
| Microfrontend | møtets uuid | Bare korrelasjon. Min side matcher på `syfo-dialog`. |

isdialogmote må lagre hvilke referanser som er åpne per møte. esyfovarsel slår dem i dag opp i sin egen tabell `UTSENDT_VARSEL`.

## Sykmeldt

Lenkebase i prod er `https://www.nav.no/syk/dialogmoter`. Ekstern varsling er `ExternalNotification.smsOnly()`, som gir samme foretrukne kanal som i dag (SMS med standardtekst fra Min side). Ditt Sykefravær-meldinger får `visibleUntil` = nå + 4 uker.

| Hendelse | Brukervarsel | Ditt Sykefravær | Microfrontend |
| --- | --- | --- | --- |
| INNKALT | `OPPGAVE` «Du er innkalt til dialogmøte - vi trenger svaret ditt», lenke `{base}/sykmeldt`, SMS | Opprett «Du er innkalt til dialogmøte - vi trenger svaret ditt». Lukk tidligere INNKALT, NYTT_TID_STED og ENDRET for møtet. | `microfrontendEnable("syfo-dialog", visibleUntil = …)` |
| NYTT_TID_STED | `OPPGAVE` «Dialogmøtet med Nav er flyttet», lenke `{base}/sykmeldt`, SMS | Opprett «Du har mottatt et brev om endret dialogmøte». Lukk som over. | Ny enable med nytt `visibleUntil`. Siste kommando vinner. |
| ENDRET | `OPPGAVE` «Dialogmøtet med Nav er endret», lenke `{base}/sykmeldt`, SMS | Som NYTT_TID_STED | Som NYTT_TID_STED |
| AVLYST | `BESKJED` «Dialogmøtet med Nav er avlyst», lenke `{base}/sykmeldt`, SMS | Opprett «Du har mottatt et brev om avlyst dialogmøte». Lukk INNKALT, NYTT_TID_STED og ENDRET. | `microfrontendDisable("syfo-dialog")` |
| REFERAT | `BESKJED` «Du har mottatt et referat fra dialogmøte», lenke `{base}/sykmeldt/referat/{brevUuid}`, SMS | Opprett «Du har mottatt et referat fra dialogmøte». Lukk INNKALT, NYTT_TID_STED og ENDRET. | `microfrontendDisable("syfo-dialog")` |
| LEST | `brukervarselInactivate` med referansen til brevet som er lest | Ingen endring | Ingen endring |

- **Lukking av brukervarsler:** isdialogmote sender i dag LEST for åpne INNKALT- og NYTT_TID_STED-varsler før avlysning, nytt tid/sted og referat. Det blir `brukervarselInactivate` for hver referanse, sendt før det nye varselet. Gjør det samme for ENDRET.
- **`messageType`:** Ditt Sykefravær krever en stabil kategori. esyfovarsel bruker `ESYFOVARSEL_DIALOGMOTE_<TYPE>`, og ENDRET blir `ESYFOVARSEL_DIALOGMOTE_ENDRET`. Behold verdiene hvis analyse eller Flex er avhengig av dem; avklar ellers nye verdier med Flex.
- **`visibleUntil` for microfrontend:** esyfovarsel skjuler panelet fra første jobbkjøring kl. 07 på møtedagen. Anbefalingen er møtetidspunktet, slik at panelet vises fram til møtet starter. Velg starten av møtedagen hvis dere vil ha uendret oppførsel. Grensen er 335 dager fram.

## Delt microfrontend

`syfo-dialog` er ett kort, «Dialogmøte med Nav». Det viser dialogmøtepanelet når siste brev er en innkalling eller endring, ellers møtebehovpanelet når et svar etterspørres, og ellers ingenting. Både isdialogmote og syfomotebehov skrur kortet av og på, og i Budstikka vinner siste kommando per sykmeldt og microfrontend.

Fordi kortet ikke viser noe når ingenting er aktuelt, er en overflødig enable ufarlig. Den eneste farlige feilen er en disable mens den andre produsenten trenger kortet. Det gir to regler:

- **isdialogmote** sender enable med `visibleUntil` ved innkalling, nytt tid/sted og endring, og disable ved avlysning og referat. Den trenger ikke vite noe om møtebehov.
- **syfomotebehov** sender enable når den ber om svar på møtebehov, og bare når det ikke finnes et aktivt møte, slik som i dag. Den sender **aldri disable mens det finnes et aktivt møte**, verken når møtebehovet ferdigstilles ved innkalling eller når kandidaten avsluttes. syfomotebehov vet om aktive møter fra statusendringene fra isdialogmote.

Dette erstatter flettingen esyfovarsel gjør i tabellen `MIKROFRONTEND_SYNLIGHET`.

Det som gjenstår, er enten ufarlig eller likt som i dag:

- En møtebehov-enable som kommer rett før statusendringen for en innkalling, overstyrer den planlagte skjulingen. Kortet står da på etter møtet uten å vise noe, til avlysning eller referat skrur det av.
- Etter en avlysning kommer ikke møtebehovpanelet tilbake av seg selv. Slik er det også i dag.

**Rekkefølge i overgangen.** syfomotebehov må flytte sin del av microfrontenden til Budstikka, med regelen over, før eller samtidig med isdialogmote. Flytter isdialogmote først, ser ikke esyfovarsel lenger dialogmøtet. Den skrur da av kortet når møtebehovet ferdigstilles ved innkalling. Samtidig med flyttingen må esyfovarsel slutte å la `SM_DIALOGMOTE_SVAR_MOTEBEHOV` påvirke microfrontenden, og alltid sende enable ved innkalling og nytt tid/sted for møter som fortsatt går via esyfovarsel.

## Nærmeste leder

Budstikka slår opp aktiv nærmeste leder og lederens e-postadresser ved utsending. isdialogmote kan fortsatt sjekke at leder finnes og at tilfellet er aktivt før den sender, slik den gjør i dag. Tag (merkelapp) er «Dialogmøte». Lenken til leder er dialogmøtesiden for lederrelasjonen, som esyfovarsel bygger som `{base}/arbeidsgiver/{narmesteLederId}`. Id-en har isdialogmote fra sitt eget lederoppslag. E-posttekstene er HTML med hilsen; bruk `Kalenderavtale.EmailNotification.html` og `Arbeidsgivervarsel.HtmlEmailNotification` med statisk, reviewet markup.

### Saken

Lag én `Arbeidsgiversak` per møte og bruk den på alle kall til leder:

```kotlin
val sak =
    Arbeidsgiversak(
        sakId = moteUuid.toString(),
        title = "Dialogmøte med $fulltNavn",
        link = lederlenke,
        hardDeleteAt = motetidspunkt + 28.days,
    )
```

Budstikka oppretter saken første gang et kall trenger den. Det finnes ikke noe eget kall for det. Tittelen settes da og kan ikke endres. Slettefristen flyttes med `arbeidsgiversakStatus` når møtet får nytt tidspunkt.

Budstikka garanterer foreløpig ikke at leveransene kommer fram i samme rekkefølge som de sendes (se [sende-varsler.md](sende-varsler.md#sak-og-kalenderavtale)). En oppdatering som kommer før avtalen, prøves på nytt til avtalen finnes. Send likevel kallene i rekkefølgen under.

### INNKALT

1. `dineSykmeldteVarselCreate(oppgavetype = DIALOGMOTE_INNKALLING, text = "Innkalling til dialogmøte", visibleUntil = nå + 4 uker)`.
2. `kalenderavtaleCreate(reference = arbeidsgiverbrevUuid, sak = sak, text = "Dialogmøte med {fornavn}", link = lederlenke, startAt = møtetidspunkt, email = innkallingstekstene)`. Saken opprettes her.

### NYTT_TID_STED og ENDRET

1. `dineSykmeldteVarselCreate(oppgavetype = DIALOGMOTE_ENDRING, text = "Endring av dialogmøte")`.
2. `arbeidsgiversakStatus(sak = sak.copy(hardDeleteAt = nyttMotetidspunkt + 28.days), status = MOTTATT)`. Bruk den samme oppdaterte saken i resten av kallene.
3. `kalenderavtaleUpdate(reference = forrige avtales referanse, tilstand = AVLYST, text = "Dialogmøte med {fornavn} er avlyst")` uten e-post.
4. `kalenderavtaleCreate(reference = nytt arbeidsgiverbrevUuid, sak = sak, text = "Dialogmøte med {fornavn} er flyttet" eller "… er endret", startAt = nytt møtetidspunkt, email = endringstekstene)`.

Start og slutt på en kalenderavtale kan ikke endres i Fager. Derfor avlyses den gamle avtalen og en ny opprettes. esyfovarsel sender e-post både på avlysningen og den nye avtalen. Med e-post bare på den nye avtalen får lederen én e-post.

### AVLYST

1. `dineSykmeldteVarselCreate(oppgavetype = DIALOGMOTE_AVLYSNING, text = "Avlysning av dialogmøte")`.
2. `kalenderavtaleUpdate(tilstand = AVLYST, text = "Dialogmøte med {fornavn} er avlyst", email = avlysningstekstene)`.
3. `arbeidsgiversakStatus(sak = sak, status = FERDIG)`.

### REFERAT

1. `kalenderavtaleUpdate(tilstand = AVHOLDT, text = "Dialogmøte med {fornavn} er avholdt")` uten e-post.
2. `arbeidsgiversakStatus(sak = sak, status = FERDIG)`.
3. `arbeidsgivervarselCreate(recipient = NarmesteLeder(sykmeldt), htmlEmail = referattekstene, sak = sak, text = "Du har mottatt et referat fra dialogmøte", link = Dine Sykmeldte-lenken for lederen)`. Standard sendevindu er greit, fordi beskjeden ikke lukkes.

esyfovarsel sender ikke noe til Dine Sykmeldte ved referat. `DIALOGMOTE_REFERAT` finnes fra kontrakt 0.6.0 hvis produktet ønsker det.

### SVAR fra leder

- `kalenderavtaleUpdate` på gjeldende avtale med ny tilstand:
  - KOMMER → `ARBEIDSGIVER_HAR_GODTATT`
  - KOMMER_IKKE → `ARBEIDSGIVER_VIL_AVLYSE`
  - NYTT_TID_STED → `ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED`
- `dineSykmeldteVarselInactivate` for møtets åpne Dine Sykmeldte-referanser. esyfovarsel lukker i dag alle åpne Dine Sykmeldte-varsler for leder, ansatt og virksomhet, også andre enn dialogmøte. Det skal ikke videreføres.

### Lederbytte under et møte

Saken hører til lederrelasjonen. Etter et lederbytte havner nye kalenderavtaler, statusendringer og beskjeder i en ny sak for den nye lederen, med samme `sakId`. isdialogmote trenger ikke gjøre noe for det. Forrige leder mister tilgangen til den gamle saken gjennom Fagers egen tilgangsstyring.

En oppdatering av en eksisterende avtale treffer avtalen i den gamle saken, og e-post går da til den nye lederens adresser. Den nye lederen ser ikke den gamle avtalen. Trenger produktet at den nye lederen får avtalen, setter isdialogmote den gamle til `AVLYST` uten e-post og oppretter en ny med ny `reference` og samme `sak`. Lederlenken bygges fra isdialogmotes eget lederoppslag og peker på den nye relasjonen først når isdialogmote har sett byttet.

## Uten nærmeste leder

`AG_VARSEL_ALTINN_RESSURS` fra isdialogmote og syfo-dokumentporten kan bruke `arbeidsgivervarselCreate` med `Arbeidsgivervarsel.AltinnResource` og `sak`. Saken opprettes da for Altinn-ressursen, med `sakId` som grupperingsid. Dekkes av #251.

## Ting som ikke videreføres

- esyfovarsel prøver ikke sak- og kalenderkall på nytt når de feiler. Budstikka prøver på nytt innenfor attempt-budsjettet.
- LEST lukker i dag ikke Ditt Sykefravær-meldingen. Ny lukking skjer ved neste hendelse eller ved `visibleUntil`, som før.
- Resend-jobben for feilede brukervarsler i esyfovarsel sjekker ikke om møtet fortsatt er aktuelt. Budstikka sender bare innenfor attempt-budsjettet.
