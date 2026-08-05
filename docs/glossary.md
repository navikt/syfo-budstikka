# Glossar — syfo-budstikka

Dette er det kanoniske språket for domenediskusjoner, issues og dokumentasjon.
Stabile kode- og kontraktsnavn står i parentes der koblingen er nyttig.

## Aktører

**Produsent**:
Fagsystemet som ber budstikka formidle noe og eier innholdet og tidspunktet.
_Unngå_: avsender, klient

**Mottaker**:
Personen eller virksomheten en formidling er rettet mot.
_Unngå_: recipient i norsk prosa

**Sykmeldt**:
Personen sykefraværet gjelder.
_Unngå_: bruker, pasient, arbeidstaker

**Nærmeste leder**:
Arbeidsgiverens representant som følger opp den sykmeldte.
_Unngå_: leder når relasjonen er relevant

**Arbeidsgiver**:
Virksomheten den sykmeldte har arbeidsforhold hos.
_Unngå_: bedrift, org

## Formidling

**Formidling (`Dispatch`)**:
En produsents anmodning om å gjøre innhold tilgjengelig for en mottaker gjennom én kanal.
_Unngå_: melding når både anmodningen og det leverte innholdet kan menes

**Kanal**:
Måten innholdet gjøres tilgjengelig på, for eksempel Min side, Dine Sykmeldte eller brev.

**Varsel**:
En formidling som gjør mottakeren oppmerksom på en beskjed eller oppgave.
_Unngå_: notifikasjon

**Varseltype**:
Om et brukervarsel er en beskjed eller en oppgave.
_Unngå_: oppgavetype

**Microfrontend**:
En innebygd flate som kan gjøres synlig eller skjules på Min side.

**Brev**:
En fysisk eller digital postforsendelse.

**Ekstern varsling**:
En SMS eller e-post som varsler om innhold i en annen flate.

**Digital kontaktstatus**:
KRR-opplysningen om en person kan varsles digitalt.
_Unngå_: reservasjon når også manglende verifisert kontaktinformasjon kan være årsaken

**Oppgavetype**:
Kategorien som grupperer et ledervarsel i Dine Sykmeldte.
_Unngå_: hendelsestype, varseltype

## Livsløp

**Referanse (`reference`)**:
Produsentens identifikator som knytter en opprettelse til en senere ferdigstilling.
_Unngå_: hendelses-ID, korrelasjons-ID

**Opprettelse (`OPPRETT`)**:
En formidling som gjør nytt innhold tilgjengelig for mottakeren.

**Ferdigstilling (`FERDIGSTILL`)**:
En formidling som lukker eller skjuler tidligere opprettet innhold.
_Unngå_: sletting

**Leveranse (`Delivery`)**:
Resultatet av å tilpasse én formidling til én mottaker og én kanal.

**Sendevindu**:
Tidsrommet en formidling kan gjøres tilgjengelig i.

**Brev som reservekanal (`BrevFallback`)**:
Et brev som sendes når mottakeren ikke kan varsles digitalt.

## Sammenhenger

- En **produsent** sender en **formidling** til én **mottaker** gjennom én **kanal**.
- En formidling kan gi én eller flere **leveranser** når en reservekanal er valgt.
- En **ferdigstilling** bruker samme **referanse** som opprettelsen den skal lukke.

## Avklart tvetydighet

`Reservasjon` brukes bare når KRR faktisk oppgir reservasjon som årsak; den bredere
beslutningen om digital rekkevidde omtales som **digital kontaktstatus**.
