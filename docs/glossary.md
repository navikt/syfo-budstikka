# Glossar — syfo-budstikka

syfo-budstikka er en **domeneblind** ruter som dispatcher noe til en recipient på riktig
channel, uten å kjenne domenet (dialogmøte, aktivitetskrav osv.). Ordlista under er det
kanoniske språket for domenet — velg disse ordene i kode, kontrakt, issues og docs.

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

**(`eventId`)**:
Produsentens unike id per melding. Brukes til dedup/idempotens og til å korrelere ett
hendelsesløp i logg. Ligger autoritativt i payloaden og speiles som Kafka-header for å
dedup-e ved inntak uten å deserialisere. Ulik `reference`: `eventId` er unik per hendelse,
mens `reference` kobler flere hendelser (create→ferdigstill).
_Unngå_: meldings-id, korrelasjons-id (i kode/logg heter feltet `eventId`)

**Reference (`reference`)**:
Produsentens id som kobler en opprettet dispatch til senere lukking. Budstikka matcher kun
på den og kjenner ikke betydningen.
_Unngå_: referanse (legacy-ord)

**Match key (`match key`)**:
Nøkkelen som brukes for å matche inactivate mot tidligere create. I modellen er matchen
`(reference, recipient_id, channel)`.
_Unngå_: matchnøkkel (legacy-ord i kodekontekst)

**Recipient match-id (`recipient_id`)**:
Recipient-identifikatoren i `delivery` som inngår i match key (personident eller orgnummer).
For inactivate-hendelser er dette samme id som konsumenten kjenner ved create.

**Create (`operation=CREATE`)**:
Å be budstikka opprette en ny dispatch.
_Unngå_: opprett (legacy-ord i kodekontekst)

**Ferdigstill**:
Å lukke en tidligere dispatch hos recipienten. Channel-spesifikk.

**Inactivate (`operation=INACTIVATE`)**:
Budstikkas lukking av en dispatch på én channel, avledet fra den lagrede create-raden.
_Unngå_: inaktiver (legacy-ord i kodekontekst)

## Levering

**CAS (compare-and-set)**:
Et atomisk update-mønster der raden bare oppdateres hvis den fortsatt har forventet state
(for eksempel `... WHERE state='CLAIMED'`). Brukes for å unngå dobbeltprosessering når
flere workere konkurrerer om samme rad.

**Delivery (`delivery`)**:
Én dispatch til én recipient på én channel, som budstikka utfører og sporer til den er terminal.
_Unngå_: leveranse (legacy-ord)

**SendingWindow**:
Tidsrommet en dispatch faktisk sendes i — løpende, eller innenfor NKS' åpningstid.

**DeathGate**:
Kontrollen som stanser dispatch til en person registrert som død.

**BrevFallback**:
Å sende brev når recipienten ikke kan varsles digitalt.
