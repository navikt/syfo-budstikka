# Kontrakt / kanal-DTO-er — syfo-budstikka

Denne siden beskriver **faktisk implementert kontrakt i kode nå**.

Kilde i kode:
- `src/main/kotlin/no/nav/budstikka/domain/dispatch/Dispatch.kt`
- `src/main/kotlin/no/nav/budstikka/domain/dispatch/Create.kt`
- `src/main/kotlin/no/nav/budstikka/domain/dispatch/Inactivate.kt`
- `src/main/kotlin/no/nav/budstikka/domain/dispatch/CommonTypes.kt`
- `src/main/kotlin/no/nav/budstikka/domain/decision/DispatchDraftMapping.kt`

## Konvolutt

```kotlin
@Serializable
data class Dispatch(
    val eventId: UUID,
    val reference: String,
    val content: DispatchContent,
)

@Serializable
sealed interface DispatchContent {
    val partitionKey: String
}
```

- `eventId`: idempotens/korrelasjon.
- `reference`: kobling på tvers av hendelser (create/ferdigstill).
- `partitionKey`: Kafka-record key (beregnes per variant).

> **Planlagt endring (ADR 0008 / B61, #125):** `eventId` FJERNES fra `Dispatch` og
> leveres KUN som Kafka-header (`DispatchHeader.EVENT_ID`) — konvolutten blir
> `{ reference, content }`. eventId er en teknisk id (dedup og korrelasjon), ikke domenedata. Blokka over
> speiler dagens kode; oppdateres når parse-ved-ingest implementeres.

## Viktige kontraktprinsipper (B22/B23)

- Kontrakten er **sealed og typet**: operation ligger i typen (`*Create`, `*Inactivate`, `MicrofrontendEnable/Disable`), ikke i et løst enum-felt.
- Budstikka bruker en **nøytral kontraktmodell** (`Dispatch`/`DispatchContent`) og speiler ikke nedstrøms API-er direkte.
- Ulovlige kombinasjoner skal være urepresenterbare i typen, ikke håndteres sent i runtime.

## Hvorfor `eventId` ikke er Kafka key

- `eventId` er unik per hendelse og brukes til dedup/korrelasjon.
- Kafka key (`partitionKey`) brukes for partisjonering/ordering per recipient.
- Derfor brukes recipient-basert key i variantene, ikke `eventId`.

## Header-kontrakt

`DispatchHeader.EVENT_ID = "eventId"` er del av kontrakten.

- Headeren bærer `eventId` (unik per hendelse, dedup/korrelasjon).
- Konsumenten bruker headeren for dedup uten å avhenge av payload-skjemaet.
- **ADR 0008 / B61:** headeren er den ENESTE og AUTORITATIVE kilden for eventId — den er
  obligatorisk (manglende/ugyldig → dead-letter), og eventId finnes ikke i payloaden.
  (Tidligere B54: headeren speilet `Dispatch.eventId` og payloaden var autoritativ — reversert.)

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
- `ExternalVarsling(channels, smsText, emailTitle, emailText)`
- `DistributionType`: `IMPORTANT`, `OTHER`
- `BrevFallback(journalpostId, distributionType)`
- `SendingWindow`: `ONGOING`, `NKS_OPENING_HOURS`
- `Tag`: `DIALOGMOETE`, `OPPFOELGING`
- `AltinnResourceId`: `DIALOGMOETE`
- `ArbeidsgiverMeldingstype`: `BESKJED`, `OPPGAVE`
- `Oppgavetype` (LEDERVARSEL, ADR 0008): lukket enum, case-navn = budstikkas domeneord + `wireValue` = dinesykmeldtes streng. Representativ verdi nå: `DIALOGMOTE_INNKALLING`; resten additivt ved onboarding.
- `Sakstilknytning(sakId)`

Viktige valg:
- `DittSykefravaerCreate` har ikke eget `variant`-felt i kontrakten nå.
- `ArbeidsgiverRecipient` er sealed valg (`NarmesteLeder` eller `AltinnResource`) og kombineres ikke i samme event.

## Dispatch-varianter

| Variant (`type`)               | Klasse | `partitionKey` |
|--------------------------------|---|---|
| `BrukervarselCreate`           | `BrukervarselCreate` | `personIdentifier.value` |
| `LedervarselCreate`            | `LedervarselCreate` | `sykmeldt.value` |
| `DittSykefravaerCreate`        | `DittSykefravaerCreate` | `personIdentifier.value` |
| `ArbeidsgivervarselCreate`     | `ArbeidsgivervarselCreate` | `orgnummer.value` |
| `BrevCreate`                   | `BrevCreate` | `personIdentifier.value` |
| `BrukervarselInactivate`       | `BrukervarselInactivate` | `sykmeldt.value` |
| `LedervarselInactivate`        | `LedervarselInactivate` | `sykmeldt.value` |
| `DittSykefravaerInactivate`    | `DittSykefravaerInactivate` | `sykmeldt.value` |
| `ArbeidsgivervarselInactivate` | `ArbeidsgivervarselInactivate` | `orgnummer.value` |
| `MicrofrontendEnable`          | `MicrofrontendEnable` | `personIdentifier.value` |
| `MicrofrontendDisable`         | `MicrofrontendDisable` | `personIdentifier.value` |

Merk:
- `MicrofrontendDisable` har `@SerialName("MicrofrontendDisable")` (med k i type-navnet).
- Inactivate-typene bruker `@SerialName("referanse")` på feltet `reference` for wire-kompatibilitet.

## Ledervarsel-resolusjon (B24)

**B24: budstikka resolver nærmeste leder selv.** Kontrakten bærer `(sykmeldt, orgnummer)`
— IKKE NL-fnr. Budstikka slår opp aktiv leder i narmesteleder-registeret i beslutnings-
fasen (som KRR/PDL). Partisjonsnøkkel = `sykmeldt` (stabilt anker; NL er ukjent ved
publisering og kan byttes). Eliminerer dagens dobbeltoppslag i esyfovarsel.

Status nå:
- Kontraktformen følger B24 (`LedervarselCreate` har `sykmeldt` + `orgnummer`, ikke NL-fnr).
- Selve NL-oppslaget er ikke koblet inn i runtime ennå.

## Ledervarsel-kanal: rent in-app (B62, ADR 0009)

LEDERVARSEL leveres til `team-esyfo.dinesykmeldte-hendelser-v2` og vises som et
**in-app aktivitetsvarsel** i Dine Sykmeldte-oversikten (`dinesykmeldte-backend`
gjør kun et DB-insert). Kanalen har **ingen ekstern bærer** (SMS/e-post).

- `LedervarselCreate` bærer `oppgavetype: Oppgavetype` (påkrevd, ADR 0008) —
  konsumentens PK `(id, oppgavetype)` + UI-gruppering.
- `LedervarselCreate` har **ikke** `externalVarsling` (falsk affordance, fjernet i v1).
- `sendingWindow` beholdes; default = `ONGOING` (LØPENDE), som ren in-app.
- **Ekstern varsling til nærmeste leder** = egen `ArbeidsgivervarselCreate` med
  `NarmesteLeder(sykmeldt)`-mottaker (B6: flere kanaler → flere hendelser; B32).
- Wire: `DineSykmeldteHendelse` (JSON), Kafka-key = `reference` (ikke fnr).

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
- kanal er implisitt i typen (`BrukervarselInactivate`, `LedervarselInactivate`, `DittSykefravaerInactivate`, `ArbeidsgivervarselInactivate`)

**Recipient match-id (`recipient_id`)**:
- `recipient_id` i `delivery` er OPPRETTs partisjonsanker (id-en konsumenten kjenner ved create)
- ikke den resolverte mottakeren i nedstrøms-systemer
- forventet match mot lagret create-rad er: `(reference, recipient_id, channel)`

Avgrensninger:
- BREV er urepresenterbart for lukking (ingen `BrevInactivate`-variant).
- Microfrontend bruker eget enable/disable-par (`MicrofrontendEnable` / `MicrofrontendDisable`) utenfor reference-basert inactivate-matching.

**Lukkeoperasjon fra lagret create-rad (B39):**
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
- Delivery-eksekvering har handlere for `BRUKERVARSEL`, `LEDERVARSEL` og `MICROFRONTEND` i `DeliveryWorker` (registrert i `WorkerModule`); øvrige kanaler (`DITT_SYKEFRAVAER`, `ARBEIDSGIVERVARSEL`, `BREV`) mangler fortsatt handler.

Dette dokumentet beskriver kontrakten og mappingen slik den faktisk er i koden nå.
