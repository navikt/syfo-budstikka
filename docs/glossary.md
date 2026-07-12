# Glossar — syfo-budstikka

syfo-budstikka er en **domeneblind** ruter som formidler noe til en mottaker på riktig
kanal, uten å kjenne domenet (dialogmøte, aktivitetskrav osv.). Ordlista under er det
kanoniske språket for domenet — velg disse ordene i kode, kontrakt, issues og docs.

## Aktører

**Produsent**:
Fagsystem som ber budstikka formidle noe. Eier *hva* og *når*, og kjenner domenet.
_Unngå_: avsender, klient

**Mottaker**:
Den en formidling er rettet mot — en sykmeldt, en nærmeste leder eller en arbeidsgiver.

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
Det en produsent ber budstikka nå ut med til en mottaker. Paraply over alle former — varsel,
brev, e-post og mikrofrontend-flate — uavhengig av kanal.
_Unngå_: varselbestilling, bestilling, melding

**Domeneblind**:
At budstikka aldri forgrener på domenetype; den ruter på kanal og tekniske attributter.
Se `adr/0001-domeneblind-varselruter.md`.

**Kanal**:
Den konkrete veien en formidling når mottakeren (Min side-brukervarsel, Dine Sykmeldte,
Ditt Sykefravær, arbeidsgivervarsel, brev, mikrofrontend).

**Flate**:
Mottakerens skjermside der en formidling vises (f.eks. Min side). Kanalen er veien dit.
_Unngå_: kanal (kanal og flate er ikke det samme)

**Varsel**:
En formidling som gir mottakeren beskjed om noe — en beskjed eller en oppgave. Én av flere
former for formidling.
_Unngå_: notifikasjon

**Mikrofrontend**:
En innebygd flate budstikka slår på eller av på en mottakers Min side. Ikke et varsel, men en
av/på-synlighet uten mottaker-lukking.

**Brev**:
Fysisk eller digital postforsendelse. Kan ikke ferdigstilles.

**Ekstern varsling**:
Påminnelse via SMS eller e-post i tillegg til flaten.

**Reservasjon**:
Mottakerens reservasjon mot digital kontakt. Styrer kun ekstern varsling og brevfallback —
ikke om varselet vises på flaten.

## Kobling og lukking

**Hendelses-id (`eventId`)**:
Produsentens unike id per hendelse. Brukes til dedup/idempotens og til å korrelere ett
hendelsesløp i logg. Ligger autoritativt i payloaden og speiles som Kafka-header for å
dedup-e ved inntak uten å deserialisere. Ulik referanse: `eventId` er unik per hendelse,
referanse kobler flere hendelser (opprett→ferdigstill).
_Unngå_: meldings-id, korrelasjons-id (i kode/logg heter feltet `eventId`)

**Referanse**:
Produsentens id som kobler en opprettet formidling til senere lukking. Budstikka matcher kun
på den og kjenner ikke betydningen.

**Matchnøkkel**:
Mottaker-ankeret produsenten kjenner ved opprettelse (sykmeldt-ident eller orgnummer), brukt
til å finne igjen en formidling for lukking.
_Unngå_: anker, mottakerident

**Opprett**:
Å be budstikka formidle noe nytt.

**Ferdigstill**:
Å lukke en tidligere formidling hos mottakeren. Kanalspesifikk.

**Inaktiver**:
Budstikkas lukking av en formidling på én kanal, avledet fra den lagrede opprettelsen.

## Levering

**Leveranse**:
Én formidling til én mottaker på én kanal, som budstikka utfører og sporer til den er terminal.

**SendingWindow**:
Tidsrommet en formidling faktisk sendes i — løpende, eller innenfor NKS' åpningstid.

**Død-gate**:
Kontrollen som stanser formidling til en person registrert som død.

**Brevfallback**:
Å sende brev når mottakeren ikke kan varsles digitalt.
