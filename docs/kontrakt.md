# Kontrakt / kanal-DTO-er — syfo-budstikka

Denne siden beskriver **faktisk implementert kontrakt i kode nå**.

Kilde i kode:

- `kontrakt/src/main/kotlin/no/nav/budstikka/contract/Budstikka.kt` (produsent-API)
- `kontrakt/src/main/kotlin/no/nav/budstikka/contract/EventId.kt`
- `kontrakt/src/main/kotlin/no/nav/budstikka/contract/EncodedDispatch.kt`
- `kontrakt/src/main/kotlin/no/nav/budstikka/contract/InternalBudstikkaWire.kt`
- `kontrakt/src/main/kotlin/no/nav/budstikka/contract/Dispatch.kt`
- `kontrakt/src/main/kotlin/no/nav/budstikka/contract/Create.kt`
- `kontrakt/src/main/kotlin/no/nav/budstikka/contract/Inactivate.kt`
- `kontrakt/src/main/kotlin/no/nav/budstikka/contract/CommonTypes.kt`
- `kontrakt/src/main/kotlin/no/nav/budstikka/contract/Identifiers.kt` (`PersonIdentifier`, `Orgnummer`, `DispatchHeader`)
- `src/main/kotlin/no/nav/budstikka/domain/decision/DispatchDraftMapping.kt`

## Produsent-API vs. rå wire

Kontrakten har to lag, og de skal ikke blandes:

- **Produsent-API**: en produsent bruker kun `Budstikka.<variant>(...)` (f.eks. `Budstikka.brukervarselCreate(...)`).
  Kallet tar imot en `EventId` produsenten selv har opprettet og lagret sammen med eget arbeid FØR første
  sending, og gjenbruker samme `EventId` ved retry — budstikka dedupliserer da en redelivery i stedet for å
  varsle personen to ganger. Kallet returnerer en `EncodedDispatch` med ferdig `topic`, `key`, `value` og
  `headers`, klar til å legges på en `ProducerRecord`.
- **Konsumentens rå wire**: `Dispatch`, `DispatchContent`, `dispatchJson` og `DispatchHeader`, samt de
  serialiserbare DTO-ene, er budstikkas egen dekode- og rutingmaskineri — ikke en del av produsent-API-et.
  De krever `@OptIn(InternalBudstikkaWire::class)` og skal ikke settes sammen manuelt av en produsent:
  gjør man det likevel, mister man garantien om at topic, key, headers og payload alltid stemmer overens.

Enkelte rå varianter (f.eks. `DittSykefravaerCreate`, `ArbeidsgivervarselCreate`) finnes i wire-modellen for
dekoding og fremtidig evolusjon, men er **ikke sendbare** før de også er lagt til som funksjon på
`Budstikka`-fasaden: budstikka ville akseptert dem på topic uten å levere dem videre.

## Konvolutt

```kotlin
@Serializable
data class Dispatch(
    val reference: String,
    val content: DispatchContent,
)

@Serializable
sealed interface DispatchContent {
    val partitionKey: String
}
```

- `reference`: kobling på tvers av hendelser (create/ferdigstill).
- `partitionKey`: Kafka-record key (beregnes per variant).

> **`eventId` er ikke i konvolutten.** Den leveres kun som Kafka-header
> (`DispatchHeader.EVENT_ID`) og er der eneste og autoritative kilde. eventId er en teknisk id
> (dedup og korrelasjon), ikke domenedata. Blokka over speiler `Dispatch.kt`.

## Viktige kontraktprinsipper

- Kontrakten er **sealed og typet**: operation ligger i typen (`*Create`, `*Inactivate`, `MicrofrontendEnable/Disable`),
  ikke i et løst enum-felt.
- Budstikka bruker en **nøytral kontraktmodell** (`Dispatch`/`DispatchContent`) og speiler ikke nedstrøms API-er
  direkte.
- Ulovlige kombinasjoner skal være urepresenterbare i typen, ikke håndteres sent i runtime.

## Hvorfor `eventId` ikke er Kafka key

- `eventId` er unik per hendelse og brukes til dedup/korrelasjon.
- Kafka key (`partitionKey`) brukes for partisjonering/ordering per recipient.
- Derfor brukes recipient-basert key i variantene, ikke `eventId`.

## Header-kontrakt

`DispatchHeader.EVENT_ID = "eventId"` er del av kontrakten.

- Headeren bærer `eventId` (unik per hendelse, dedup/korrelasjon).
- Konsumenten bruker headeren for dedup uten å avhenge av payload-skjemaet.
- Headeren er den eneste og autoritative kilden for eventId. Den er obligatorisk
  (manglende eller ugyldig verdi sendes til dead-letter), og eventId finnes ikke i payloaden.

## Serialisering

`dispatchJson` er satt opp slik:

```kotlin
Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}
```

Det betyr:

- polymorfi via feltet `type` (`@SerialName` per variant)
- additive felter er bakoverkompatible.

## Identifikatorer

- `PersonIdentifier(value: String)` (11 siffer), `toString()` maskeres som `***`.
- `Orgnummer(value: String)` (9 siffer), `toString()` maskeres som `***`.

## Felles typer i kontrakten

- `Varseltype`: `BESKJED`, `OPPGAVE`
- `ExternalChannel`: `SMS`, `EMAIL`
- `ExternalVarsling`: konstruktøren er privat. Bruk `ExternalVarsling.smsAndEmail(smsText, emailTitle, emailText)`,
  `ExternalVarsling.smsOnly(smsText)` eller `ExternalVarsling.emailOnly(emailTitle, emailText)`. Tekstfeltene er valgfrie.
- `DistributionType`: `IMPORTANT`, `OTHER`
- `BrevFallback(journalpostId, distributionType)`
- `SendingWindow`: `ONGOING`, `BUDSTIKKA_OPENING_HOURS`
- `Tag`: `DIALOGMOETE`, `OPPFOELGING`
- `AltinnResourceId`: `DIALOGMOETE`
- `ArbeidsgiverMeldingstype`: `BESKJED`, `OPPGAVE`
- `Oppgavetype` (LEDERVARSEL): lukket enum, case-navn = budstikkas domeneord + `wireValue` = dinesykmeldtes streng. Representativ verdi nå: `DIALOGMOTE_INNKALLING`; resten additivt ved onboarding.
- `Sakstilknytning(sakId)`

Viktige valg:

- `DittSykefravaerCreate` har ikke eget `variant`-felt i kontrakten nå.
- `ArbeidsgiverRecipient` er sealed valg (`NarmesteLeder` eller `AltinnResource`) og kombineres ikke i samme event.

## Dispatch-varianter

| Variant (`type`)               | Klasse                         | `partitionKey`           |
|--------------------------------|--------------------------------|--------------------------|
| `BrukervarselCreate`           | `BrukervarselCreate`           | `personIdentifier.value` |
| `LedervarselCreate`            | `LedervarselCreate`            | `sykmeldt.value`         |
| `DittSykefravaerCreate`        | `DittSykefravaerCreate`        | `personIdentifier.value` |
| `ArbeidsgivervarselCreate`     | `ArbeidsgivervarselCreate`     | `orgnummer.value`        |
| `BrevCreate`                   | `BrevCreate`                   | `personIdentifier.value` |
| `BrukervarselInactivate`       | `BrukervarselInactivate`       | `sykmeldt.value`         |
| `LedervarselInactivate`        | `LedervarselInactivate`        | `sykmeldt.value`         |
| `DittSykefravaerInactivate`    | `DittSykefravaerInactivate`    | `sykmeldt.value`         |
| `ArbeidsgivervarselInactivate` | `ArbeidsgivervarselInactivate` | `orgnummer.value`        |
| `MicrofrontendEnable`          | `MicrofrontendEnable`          | `personIdentifier.value` |
| `MicrofrontendDisable`         | `MicrofrontendDisable`         | `personIdentifier.value` |

Merk:

- `MicrofrontendDisable` har `@SerialName("MicrofrontendDisable")` (med k i type-navnet).
- Inactivate-typene bruker `@SerialName("referanse")` på feltet `reference` for wire-kompatibilitet.

## Ledervarsel: ingen NL-oppslag i dag

**Budstikka slår ikke opp nærmeste leder.** Produsenten sender `(sykmeldt, orgnummer, oppgavetype)`, og
budstikka videresender dette som in-app-hendelse til Dine Sykmeldte. Kontrakten tar aldri imot fnr til
nærmeste leder, verken i create eller inactivate. Partisjonsnøkkel = `sykmeldt` (stabilt anker).

## Ledervarsel-kanal: rent in-app

LEDERVARSEL leveres til `team-esyfo.dinesykmeldte-hendelser-v2` og vises som et
**in-app aktivitetsvarsel** i Dine Sykmeldte-oversikten (`dinesykmeldte-backend`
gjør kun et DB-insert). Kanalen har **ingen ekstern bærer** (SMS/e-post).

- `LedervarselCreate` bærer `oppgavetype: Oppgavetype` (påkrevd) —
  konsumentens PK `(id, oppgavetype)` + UI-gruppering.
- `LedervarselCreate` har **ikke** `externalVarsling` (falsk affordance, fjernet i v1).
- `sendingWindow` beholdes; default = `ONGOING` (LØPENDE), som ren in-app.
- **Ekstern varsling til nærmeste leder** = egen `ArbeidsgivervarselCreate` med
  `NarmesteLeder(sykmeldt)`-mottaker.
- Wire: `DineSykmeldteHendelse` (JSON), Kafka-key = `reference` (ikke fnr).

### Navngiving i produsent-API-et

Fasadefunksjonene heter `dineSykmeldteVarselCreate`/`dineSykmeldteVarselInactivate` —
de navngir **kanalen**, ikke mottakeren. «Ledervarsel» ville lest som «varsel til leder»
og blitt tvetydig den dagen Arbeidsgivervarsel-kanalen også når lederen. Internt i
budstikka og på wire beholder varianten det etablerte domenenavnet `LedervarselCreate`;
wire-navn er kompatibilitetskritiske og endres ikke. Samme akse gjelder de andre
fasadefunksjonene: `brukervarsel*` er Min sides produktnavn (ikke «alle kanaler til
sykmeldt»), `dittSykefravaer*` og `arbeidsgivernotifikasjon*` navngir kanal/produkt når
de blir sendbare.

## Brev: alltid tvungen sentral print

`brevCreate` bestiller dokdistfordeling med `tvingKanal: PRINT` — brevet går alltid på
papir, også for personer uten Reservasjon som ellers ville fått det digitalt i Digipost.
Dette er et bevisst avvik fra dokdist ordinær løype, og navnet «Brev» er derfor ærlig.
(I esyfovarsel lurte «brev»-navnet folk til å tro alt gikk på post, mens ikke-reserverte
fikk det i Digipost.) Ordinær løype **vil trengs ved migrering fra esyfovarsel**: der går
7 av 8 dokdist-kallsteder ordinært (`tvingSentralPrint = false`), inkludert tre
varseltyper med brev som eneste kanal uten KRR-sjekk — bare
aktivitetsplikt-renotifiseringen tvinger print. Ordinær løype må da bli en egen variant
med et navn som sier at kanalvalget ligger hos dokdist — ikke en endring av `brevCreate`.
`brevFallback` på `brukervarselCreate` bruker samme kanal og gjelder bare ved Reservasjon.

## Mapping til delivery-draft (faktisk kode)

`DispatchContent.toDeliveryDraft(reference)` mapper til:

- `operation`: `CREATE` eller `INACTIVATE`
- `channel`: `BRUKERVARSEL`, `LEDERVARSEL`, `DITT_SYKEFRAVAER`, `ARBEIDSGIVERVARSEL`, `BREV`, `MICROFRONTEND`
- `recipient`:
    - `Recipient.Person(...)` for personbaserte kanaler
    - `Recipient.Virksomhet(...)` for arbeidsgiverkanal

Dette skjer i `domain/decision/DispatchDraftMapping.kt`.

## FERDIGSTILL / Inactivate (viktig beslutning)

For lukkbare kanaler er inactivate-hendelsene bevisst **thin**:

- `reference` + typet recipient-felt (`sykmeldt` eller `orgnummer`)
- kanal er implisitt i typen (`BrukervarselInactivate`, `LedervarselInactivate`, `DittSykefravaerInactivate`,
  `ArbeidsgivervarselInactivate`)

**Recipient match-id (`recipient_id`)**:

- `recipient_id` i `delivery` er OPPRETTs partisjonsanker (id-en konsumenten kjenner ved create)
- ikke den resolverte mottakeren i nedstrøms-systemer
- forventet match mot lagret create-rad er: `(reference, recipient_id, channel)`

Avgrensninger:

- BREV er urepresenterbart for lukking (ingen `BrevInactivate`-variant).
- Microfrontend bruker eget enable/disable-par (`MicrofrontendEnable` / `MicrofrontendDisable`) utenfor reference-basert
  inactivate-matching.

**Lukkeoperasjon fra lagret create-rad:**

- Inactivate-eventet er tynt og bærer ikke alle tekniske lukkedetaljer.
- Riktig lukkeoperasjon må avledes fra tidligere create-rad.
- Dette er designretningen; selve oppslaget er fortsatt ikke implementert i runtime.

## DeathGate-selection i dag

`DispatchContent.gatedPerson()` returnerer person kun for:

- `BrukervarselCreate`
- `DittSykefravaerCreate`
- `BrevCreate`

Alle andre varianter returnerer `null` (gates ikke av `DeathGate` i dag).

## Viktig avgrensning i nåværende implementasjon

- `*Inactivate` mappes til nye `DeliveryDraft`-rader direkte.
- Oppslag på tidligere create-rad via `(reference, recipient, channel)` er **ikke implementert ennå**.
- Delivery-eksekvering har handlere for `BRUKERVARSEL`, `LEDERVARSEL`, `MICROFRONTEND` og `BREV` i `DeliveryWorker` (registrert i `WorkerModule`); `DITT_SYKEFRAVAER` og `ARBEIDSGIVERVARSEL` mangler fortsatt handler.

Dette dokumentet beskriver kontrakten og mappingen slik den faktisk er i koden nå.
