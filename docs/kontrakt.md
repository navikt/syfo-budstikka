# Contract / channel DTOs — syfo-budstikka

This page describes the contract **actually implemented in code today**.

Code sources:

- `src/main/kotlin/no/nav/budstikka/domain/dispatch/Dispatch.kt`
- `src/main/kotlin/no/nav/budstikka/domain/dispatch/Create.kt`
- `src/main/kotlin/no/nav/budstikka/domain/dispatch/Inactivate.kt`
- `src/main/kotlin/no/nav/budstikka/domain/dispatch/CommonTypes.kt`
- `src/main/kotlin/no/nav/budstikka/domain/decision/DispatchDraftMapping.kt`

## Envelope

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

- `reference`: links events across create/FERDIGSTILL.
- `partitionKey`: Kafka record key, calculated per variant.

`eventId` is not payload data. It is supplied only as the mandatory Kafka
header `DispatchHeader.EVENT_ID`; see
[ADR 0008](adr/0008-hydrert-inbox-parse-ved-ingest.md).

## Important contract principles (B22/B23)

- The contract is **sealed and typed**: operation is carried by the type
  (`*Create`, `*Inactivate`, `MicrofrontendEnable/Disable`), not a loose enum field.
- Budstikka uses a **neutral contract model** (`Dispatch`/`DispatchContent`) and
  does not mirror downstream APIs directly.
- Illegal combinations must be unrepresentable in the type, not handled late at runtime.

## Why `eventId` is not the Kafka key

- `eventId` is unique per event and is used for deduplication and correlation.
- Kafka key (`partitionKey`) is used for partitioning and ordering per recipient.
- Variants therefore use a recipient-based key, not `eventId`.

## Header contract

`DispatchHeader.EVENT_ID = "eventId"` is part of the contract.

- The header carries `eventId` (unique per event, for deduplication and correlation).
- The consumer uses the header for deduplication without depending on payload schema.
- **ADR 0008 / B61:** The header is the ONLY and AUTHORITATIVE source of
  `eventId`; it is mandatory (missing or invalid → dead letter), and `eventId`
  does not occur in the payload.

## Serialization

`dispatchJson` is configured as follows:

```kotlin
Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}
```

This provides:

- polymorphism through the `type` field (`@SerialName` per variant);
- backward-compatible additive fields.

## Identifiers

- `PersonIdentifier(value: String)` (11 digits); `toString()` masks it as `***`.
- `Orgnummer(value: String)` (9 digits); `toString()` masks it as `***`.

## Shared contract types

- `Varseltype`: `BESKJED`, `OPPGAVE`
- `ExternalChannel`: `SMS`, `EMAIL`
- `ExternalVarsling(channels, smsText, emailTitle, emailText)`
- `DistributionType`: `IMPORTANT`, `OTHER`
- `BrevFallback(journalpostId, distributionType)`
- `SendingWindow`: `ONGOING`, `NKS_OPENING_HOURS`
- `Tag`: `DIALOGMOETE`, `OPPFOELGING`
- `AltinnResourceId`: `DIALOGMOETE`
- `ArbeidsgiverMeldingstype`: `BESKJED`, `OPPGAVE`
- `Sakstilknytning(sakId)`

Important choices:

- `DittSykefravaerCreate` currently has no separate `variant` field in the contract.
- `ArbeidsgiverRecipient` is a sealed choice (`NarmesteLeder` or
  `AltinnResource`) and the two are not combined in the same event.

## Dispatch variants

| Variant (`type`) | Class | `partitionKey` |
|---|---|---|
| `BrukervarselCreate` | `BrukervarselCreate` | `personIdentifier.value` |
| `LedervarselCreate` | `LedervarselCreate` | `sykmeldt.value` |
| `DittSykefravaerCreate` | `DittSykefravaerCreate` | `personIdentifier.value` |
| `ArbeidsgivervarselCreate` | `ArbeidsgivervarselCreate` | `orgnummer.value` |
| `BrevCreate` | `BrevCreate` | `personIdentifier.value` |
| `BrukervarselInactivate` | `BrukervarselInactivate` | `sykmeldt.value` |
| `LedervarselInactivate` | `LedervarselInactivate` | `sykmeldt.value` |
| `DittSykefravaerInactivate` | `DittSykefravaerInactivate` | `sykmeldt.value` |
| `ArbeidsgivervarselInactivate` | `ArbeidsgivervarselInactivate` | `orgnummer.value` |
| `MicrofrontendEnable` | `MicrofrontendEnable` | `personIdentifier.value` |
| `MicrofrontendDisable` | `MicrofrontendDisable` | `personIdentifier.value` |

Notes:

- `MicrofrontendDisable` has `@SerialName("MicrofrontendDisable")`, including
  the `k` in the type name.
- The Inactivate types use `@SerialName("referanse")` on `reference` for wire compatibility.

## Ledervarsel resolution (B24)

**B24: Budstikka resolves the nearest leader itself.** The contract carries
`(sykmeldt, orgnummer)` — **not** an NL fnr. During the decision phase,
Budstikka looks up the active leader in the narmesteleder register (like KRR/PDL).
Partition key = `sykmeldt` (a stable anchor; NL is unknown at publishing time
and may change). This removes today's double lookup in esyfovarsel.

Current status:

- The contract form follows B24 (`LedervarselCreate` has `sykmeldt` plus
  `orgnummer`, not NL fnr).
- The NL lookup itself is not wired into runtime yet.

## Mapping to delivery draft (actual code)

`DispatchContent.toDeliveryDraft(reference)` maps to:

- `operation`: `CREATE` or `INACTIVATE`
- `channel`: `BRUKERVARSEL`, `LEDERVARSEL`, `DITT_SYKEFRAVAER`,
  `ARBEIDSGIVERVARSEL`, `BREV`, `MICROFRONTEND`
- `recipient`:
  - `Recipient.Person(...)` for person-based channels;
  - `Recipient.Virksomhet(...)` for the employer channel.

This happens in `domain/decision/DispatchDraftMapping.kt`.

## FERDIGSTILL / Inactivate (important decision)

For closeable channels, Inactivate events are deliberately **thin**:

- `reference` plus a typed recipient field (`sykmeldt` or `orgnummer`);
- the channel is implicit in the type (`BrukervarselInactivate`,
  `LedervarselInactivate`, `DittSykefravaerInactivate`,
  `ArbeidsgivervarselInactivate`).

**Recipient match ID (`recipient_id`):**

- `recipient_id` in `delivery` is the CREATE partition anchor, the ID the
  consumer knows at create time;
- it is not the resolved recipient in downstream systems;
- expected match against the stored create row is `(reference, recipient_id, channel)`.

Boundaries:

- BREV cannot be represented for closure; no `BrevInactivate` variant exists.
- Microfrontend uses its own enable/disable pair
  (`MicrofrontendEnable` / `MicrofrontendDisable`) outside reference-based
  Inactivate matching.

**Close operation from the stored create row (B39):**

- The Inactivate event is thin and carries none of the technical closing details.
- The correct close operation must be derived from the previous create row.
- This is the design direction; the lookup is not implemented in runtime yet.

## Current DeathGate selection

`DispatchContent.gatedPerson()` returns a person only for:

- `BrukervarselCreate`
- `DittSykefravaerCreate`
- `BrevCreate`

All other variants return `null`; they are not currently gated by `DeathGate`.

## Important limitation in the current implementation

- `*Inactivate` maps directly to new `DeliveryDraft` rows.
- Lookup of a prior create row through `(reference, recipient, channel)` is
  **not implemented yet**.
- Delivery execution currently has handlers for `BRUKERVARSEL`, `BREV`, and
  `MICROFRONTEND` in `DeliveryWorker`. `LEDERVARSEL`, `DITT_SYKEFRAVAER`, and
  `ARBEIDSGIVERVARSEL` do not have delivery handlers yet.

This document describes the contract and mapping as they actually exist in code today.
