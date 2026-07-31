# Glossar — syfo-budstikka

syfo-budstikka er en **domeneblind** ruter som dispatcher noe til en recipient på riktig
channel, uten å kjenne domenet (dialogmøte, aktivitetskrav osv.). Ordlista under er det
kanoniske språket for domenediskusjoner, issues og docs. Kode- og kontraktsnavn i parentes
er kryssreferanser, ikke en regel om å endre stabile identifikatorer.

## Aktører

**Produsent**:
Fagsystem som ber budstikka sende en dispatch. Eier *hva* og *når*, og kjenner domenet.
_Unngå_: avsender, klient

**Recipient (`recipient`)**:
Den en dispatch er rettet mot — en sykmeldt, en nærmeste leder eller en arbeidsgiver.
_Unngå_: mottaker (legacy-ord)

**Sykmeldt**:
Personen sykefraværet gjelder. Identifiseres med personident.
_Unngå_: bruker, pasient, arbeidstaker

**Nærmeste leder**:
Arbeidsgivers representant som følger opp den sykmeldte. Budstikka resolver hvem det er
ved sendetidspunkt (produsenten oppgir aldri leder-identiteten).
_Unngå_: leder

**Arbeidsgiver**:
Virksomheten (underenhet) den sykmeldte har arbeidsforhold hos. Identifiseres med orgnummer.
_Unngå_: bedrift, org

## Kjernebegreper

**Dispatch**:
Det en produsent ber budstikka nå ut med til en recipient. Paraply over alle former — varsel,
brev, e-post og microfrontend-flate — uavhengig av channel.
_Unngå_: varselbestilling, bestilling, melding

**Domeneblind**:
At budstikka aldri forgrener på domenetype; den ruter på channel og tekniske attributter.
Se `adr/0001-domeneblind-varselruter.md`.

**Channel (`channel`)**:
Den konkrete veien en dispatch når recipienten (Min side-brukervarsel, Dine Sykmeldte,
Ditt Sykefravær, arbeidsgivervarsel, brev, microfrontend).
_Unngå_: kanal (legacy-ord i kodekontekst)

**Varsel**:
En dispatch som gir recipienten beskjed om noe — en beskjed eller en oppgave. Én av flere
former for dispatch.
_Unngå_: notifikasjon

**Microfrontend**:
En innebygd flate budstikka slår på eller av på en recipients Min side. Ikke et varsel, men en
av/på-synlighet uten recipient-lukking.

**Brev**:
Fysisk eller digital postforsendelse. Kan ikke ferdigstilles.

**Ekstern varsling**:
Påminnelse via SMS eller e-post i tillegg til flaten.

**Reservasjon**:
Recipientens reservasjon mot digital kontakt. Styrer kun ekstern varsling og brevfallback —
ikke om varselet vises på flaten.

## Kobling og lukking

**Hendelses-ID (`eventId`)**:
Produsentens unike identifikator for én dispatch-hendelse. Ulik `reference`: hendelses-ID-en
identifiserer én hendelse, mens `reference` knytter flere hendelser sammen.
_Unngå_: meldings-id, korrelasjons-id

**Reference (`reference`)**:
Produsentens identifikator som knytter en opprettet dispatch til senere lukking. Budstikka
kjenner ikke identifikatorens domenebetydning.
_Unngå_: referanse (legacy-ord)

**Match key (`match key`)**:
Nøkkelen som brukes for å matche inactivate mot tidligere create. I modellen er matchen
`(reference, recipient_id, channel)`.
_Unngå_: matchnøkkel (legacy-ord i kodekontekst)

**Recipient match-id (`recipient_id`)**:
Den stabile recipient-identifikatoren produsenten kjenner ved create, enten personident eller
orgnummer. Den inngår i match key ved senere inactivate.

**Create (`operation=CREATE`)**:
Å be budstikka opprette en ny dispatch.
_Unngå_: opprett (legacy-ord i kodekontekst)

**Ferdigstill**:
Å lukke en tidligere dispatch hos recipienten. Channel-spesifikk.

**Inactivate (`operation=INACTIVATE`)**:
Budstikkas lukking av en dispatch på én channel, avledet fra den lagrede create-raden.
_Unngå_: inaktiver (legacy-ord i kodekontekst)

## Levering

**Delivery (`delivery`)**:
Én dispatch til én recipient på én channel, som budstikka utfører og sporer til den er terminal.
_Unngå_: leveranse (legacy-ord)

**SendingWindow**:
Tidsrommet en dispatch faktisk sendes i — løpende, eller innenfor NKS' åpningstid.

**DeathGate**:
Kontrollen som stanser dispatch til en person registrert som død.

**BrevFallback**:
Å sende brev når recipienten ikke kan varsles digitalt.
