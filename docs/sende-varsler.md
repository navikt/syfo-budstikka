# Sende varsler med Budstikka

Bruk `no.nav.syfo:budstikka-kontrakt` når en sykefraværsapp skal sende et varsel på
`team-esyfo.budstikka.v1`. [README](../README.md) viser avhengighet og komplett mapping til
`ProducerRecord`.

## Før du sender

Appen trenger minst Kotlin 2.3 og Java 21 og eier sin egen Kafka-producer. NAIS-manifestet må ha
riktig `kafka.pool`: `nav-dev` i dev og `nav-prod` i prod. Før appen kan sende, må den ha en
eksplisitt `write` ACL i topic-definisjonene i syfo-budstikka-repoet:
[`nais/topics/kafka-dev.yaml`](../nais/topics/kafka-dev.yaml) og
[`nais/topics/kafka-prod.yaml`](../nais/topics/kafka-prod.yaml).
Kontrakten publiserer ikke `kafka-clients`.

Bruk bare `Budstikka`-fasaden. Den returnerer `EncodedDispatch`, der `topic`, `key`, `value` og
`eventId`-header allerede hører sammen. Rå wire-typer er for Budstikka som konsument og skal ikke
settes sammen i en produsent.

## Støttede funksjoner

| Funksjon | Bruk |
| --- | --- |
| `brukervarselCreate` | Opprett beskjed eller oppgave på Min side. |
| `brukervarselInactivate` | Lukk et tidligere brukervarsel. |
| `dineSykmeldteVarselCreate` | Opprett in-app-aktivitet i Dine Sykmeldte. |
| `dineSykmeldteVarselInactivate` | Lukk en tidligere aktivitet i Dine Sykmeldte. |
| `arbeidsgivervarselCreate` | Send varsel til Nærmeste leder eller en Altinn-ressurs. |
| `brevCreate` | Send et dokument gjennom dokumentdistribusjon. |
| `microfrontendEnable` | Vis en mikrofrontend på Min side. |
| `microfrontendDisable` | Skjul en tidligere aktivert mikrofrontend. |

`DittSykefravaer` er ikke en funksjon på fasaden fordi den mangler runtime-støtte.

## Opprett og lukk

`reference` er produsentens egen kobling mellom en opprettelse og en senere lukking. Velg en stabil,
ikke-tom referanse som appen kan finne igjen. For `brukervarselInactivate` og
`dineSykmeldteVarselInactivate` bruker du samme `reference` og samme mottaker som ved opprettelse.

Mikrofrontend bruker et enable/disable-par. Min side matcher på mottaker og `microfrontendId`, ikke
på `reference`, men referansen skal fortsatt være med for korrelasjon og egen bokføring. Brev kan
ikke lukkes.

## EventId, retry og partisjonering

`EventId` er en teknisk id for én dispatch. Opprett den med `EventId.new()`, lagre den transaksjonelt
sammen med arbeidet som utløser varselet og gjør det før første Kafka-sending. Ved timeout, restart,
outbox-recovery eller annen retry henter appen den samme lagrede id-en. En ny id på retry kan gi et
nytt varsel i stedet for deduplisering.

Kafka-nøkkelen og `EventId` har ulike roller:

- `EncodedDispatch.key` er mottakerens id og holder varsler til samme mottaker ordnet på én partisjon.
  Den er persondata og skal ikke logges.
- `EventId` ligger i headeren `eventId` og brukes til deduplisering og korrelasjon. Den finnes ikke i
  JSON-payloaden.

`EncodedDispatch.value` inneholder varseltekst og identifikatorer. Ikke logg payload, nøkkel eller
fritekst. `reference` er produsentens korrelasjons-id og kan logges når produsenten har et konkret
operasjonelt behov og har vurdert innhold, tilgang og oppbevaringstid. Bruk en ugjennomsiktig intern
id; aldri fødselsnummer, andre direkte personidentifikatorer eller sammensatte forretningsverdier.

## Ekstern varsling og brev

Bare `brukervarselCreate` støtter `ExternalNotification`. Velg
`smsAndEmail`, `smsOnly` eller `emailOnly` når Min side-varselet også skal ha ekstern varsling.

Det samme brukervarselet kan ha `BrevFallback` når et ferdig opprettet journalpostdokument skal
sendes hvis personen ikke kan varsles digitalt. `brevCreate` brukes når appen skal sende et dokument
direkte. Dokumentdistribusjon velger normalt kanal selv; `tvingSentralPrint` er et eksplisitt unntak
for papir.

Dine Sykmeldte har bare in-app-varsel. Ikke legg til SMS- eller e-postforventninger der.

`arbeidsgivervarselCreate` bruker enten `Arbeidsgivervarsel.NarmesteLeder(sykmeldt)` eller
`Arbeidsgivervarsel.AltinnResource(resource)`. Bruk mottakerspesifikke `externalNotification`-verdier,
ikke `ExternalNotification`. Nærmeste leder-oppslaget og e-postleveringen skjer i Budstikka.
`tag` må samsvare med produsentens registrerte merkelapp, og Altinn `resource` må samsvare med
produsentens registrerte Altinn-ressurs i Arbeidsgivernotifikasjoner. Ellers feiler leveringen
terminalt.
Leveringen feiler terminalt når en aktiv nærmeste leder mangler. Når ekstern varsling er valgt,
feiler leveringen også terminalt hvis lederen mangler e-postadresse. `visibleUntil` er valgfritt og
angir når mottakerkanalen slutter å vise varselet.

## Feil og personvern

Fasaden validerer obligatoriske felt og kaster `IllegalArgumentException` med parameterets navn,
aldri verdien. Rett data og bruk fortsatt samme lagrede `EventId` når den samme dispatchen forsøkes
på nytt.

Identifikatorer, referanser, journalpost-id-er og varseltekst kan være persondata. En `reference` som
logges etter regelen over, skal behandles som pseudonyme personopplysninger. Andre identifikatorer,
payload, varseltekst og fritekst skal ikke legges i logger, exceptions eller metrics. Eksempler og
tester skal bruke tydelig syntetiske verdier.

## Versjonering og utvikling

Oppgrader kontrakten med semantisk versjonering. Les release-notatene og test produsenten mot ny
versjon før oppgradering. Første versjon, `0.1.0`, er en pilot og er ikke esyfovarsel-paritet.
`1.0.0` vurderes først etter pilot- og paritetsgjennomgang.

Budstikka utvikler fasaden consumer-first: nye funksjoner og additive felt skal ikke tvinge
produsenter over natten. En deprecering skal først dokumenteres med alternativ og migreringsfrist.
En breaking endring i producer-API-et i `0.x` krever ny minor-versjon, release-notat, migrering og en
eksplisitt, reviewet endring av kompatibilitetspolicyen eller -gaten i samme R3-leveranse. Det finnes
ingen ordinær bypass-flag.

Rå wire er utelatt fra JVM-sammenligningen av producer-API-et, men er fortsatt styrt av golden
serialiseringstester og ny-topic-policyen. En Arbeidsgivervarsel-variant som verken er eksponert i et
publisert producer-API eller sendt på topic-et, kan rettes på v1 før første bruk. Etter publisering
eller bruk krever breaking wire-endringer et nytt Kafka-topic, normalt `.v2`, med parallell migrering.

Hver ny release må ha et eget, kontrollert release-notat med riktig versjon og migrering. Publisering
startes bare av en autorisert tagg `kontrakt/vX.Y.Z` fra `main`; workflowen avviser duplikatversjoner og
publiserer først etter kompatibilitets- og consumer-sjekker. Baseline-sjekken krever i tillegg at hver
ny versjon er høyere enn den høyeste publiserte kontraktversjonen: En patch på en eldre minor-linje
(for eksempel `0.2.1` etter at `0.3.0` er publisert) støttes bevisst ikke; rettinger leveres som ny
versjon på toppen av linjen.

Kompatibilitets- og release-gatene for kontraktendringer stopper med vilje når Nav-speilet er nede
eller ligger etter release-taggene; vanlige app- og dokumentasjons-PR-er er ikke avhengige av speilet.
Vent til speilet har tatt igjen releasen, og kjør den feilede jobben på nytt.

Hvis en beskyttet release-tagg finnes, men publisering feilet før pakkeopplasting, blokkerer
kontraktrelevante PR-gater og senere releaser med vilje fordi den autoritative taggen mangler en
eksakt speilet POM. Kjør først de feilede workflow-jobbene på nytt når verken kode eller bytes må
endres. Må koden endres før publisering, skal en vedlikeholder bruke den aksepterte, kontrollerte
ruleset-bypassen til å slette den feilede, upubliserte taggen, deretter merge rettingen og opprette
release-taggen på nytt (eller bruke den eksplisitt valgte erstatningsversjonen); kontroller at verken
pakke eller GitHub Release finnes før en versjon brukes på nytt. Slett eller repoint aldri en tagg
for en versjon der pakken allerede er publisert: pakkeversjoner er uforanderlige.

Før første release må vedlikeholderne konfigurere en aktiv GitHub-tag ruleset som matcher
`refs/tags/kontrakt/v*` uten ekskluderinger og begrenser opprettelse, oppdatering og sletting av tagger.
Workflowen stopper før bygg og publisering uten denne kontrollen.

Kontrollerte bypass-aktører for organisasjon eller team kan finnes som en operasjonell nød- og
styringsmekanisme. Workflowen verifiserer ref-matching og begrensningene for opprettelse, oppdatering
og sletting, men kan ikke kontrollere bypass-aktørene fordi GitHub skjuler dem uten skrivetilgang til
ruleset. Pakkeversjoner er uforanderlige, og preflighten for eksakt versjon hindrer at publiserte bytes
overskrives. Dette er en akseptert R3-risiko og skal omtales i draft-PR-beskrivelsen.

Hvis speilverifisering eller opprettelse av GitHub Release feiler etter opplasting, kjør bare de feilede
jobbene på nytt; pakkeversjonen forblir uforanderlig, og en ny versjon kreves bare ved endrede bytes,
ikke for å prøve etterfølgende verifiserings- eller releasejobber på nytt.

Ved release oppdaterer du avhengighetsversjonen i README og holder fixture-/eksempel-Kafka-client-versjonen
lik hvis den bevisst endres; behold den bevisste Kotlin 2.3.21-gulvpinnen.
