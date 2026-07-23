# 0011: Kontrakt-API-overflate — kafka-fri grense, Kotlin DSL og navnefrys

- Status: besluttet (B64; grillet 2026-07-22)
- Dato: 2026-07-22
- Relatert: B21/B22/B29/B30/B44/B54, ADR 0010 (distribusjon), `docs/kontrakt.md`

## Kontekst

`budstikka-kontrakt` (ADR 0010) skal gjøres ergonomisk for produsent-appene. To ting måtte festes
før første publisering — begge er dyre å endre etterpå (offentlig, kompilert-mot API):

1. **Hvor høyt opp i stacken går pakken** — kun DTO-er, eller også en byggehjelp/producer?
2. **DSL-form** — hvordan gi ergonomi uten å svekke B21/B22 (ulovlige tilstander urepresenterbare).
3. **API-navnefrys** — kontrakten hadde språkmiks (`ExternalVarsling`) og drift fra låste
   beslutninger (`Tag` vs. B30s `merkelapp`). Ved `1.0.0`/prod fryses navnene.

## Beslutning

### 1. Bibliotekets grense: L3, men kafka-fri

Publisert overflate:
- **DTO-ene + `dispatchJson` + `DispatchHeader`** (gulv — konsistent serialisering + header-navn
  ett sted, ikke re-implementert per produsent).
- **DSL-builderen** (punkt 2).
- **Et rent encode-steg** som DSL-en munner ut i, med en kafka-fri data-holder:

  ```kotlin
  data class EncodedDispatch(
      val partitionKey: String,   // Kafka record key (= content.partitionKey)
      val value: String,          // dispatchJson-serialisert Dispatch
      val eventId: String,        // header-verdi, generert av lib
      val headerName: String,     // = DispatchHeader.EVENT_ID
  )
  ```

Produsenten bygger selv sin `ProducerRecord` fra dette. eventId genereres i lib-en
(`java.util.UUID.randomUUID()`, v4 — unikhet er alt som kreves for en dedup-nøkkel; ingen opt-in,
ingen ekstra avhengighet) og returneres, slik at produsenten kan logge sin egen eventId for gratis
kryss-system-korrelasjon (B45).

### 2. Kotlin DSL: required som params, valgfritt i lambda, én funksjon per variant

```kotlin
val encoded = brukervarselCreate(
    reference = "sak-123",
    personIdentifier = PersonIdentifier("..."),
    varseltype = Varseltype.BESKJED,
    text = "...",
) {
    link = "https://..."
    eksternVarsling { kanaler(EksternKanal.SMS); smsTekst("...") }
    brevFallback(journalpostId = "jp-1")
}
```

- **Required-felt er funksjonsparametre → fortsatt kompileringstid-håndhevet.** DSL-en svekker ikke
  B21/B22; den pakker kun det valgfrie inn i en lambda. AG-`recipient` (sealed valg) blir en required
  param (`narmesteLeder(...)` / `altinnRessurs(...)`) → nettopp ett valg.
- **Én top-level funksjon per variant → «nøyaktig ett content» er strukturelt garantert** (ingen
  `dispatch { pickOne() }`-omslag der man kan glemme å velge).
- `@DslMarker` på nøstede scope hindrer scope-lekkasje.
- DSL-funksjonene returnerer `EncodedDispatch` (encode + eventId skjer inni). Data class-
  konstruktørene forblir `public` for maksimal compile-time-sikkerhet ved direkte konstruksjon.

### 3. Navnefrys (kode matcher låste beslutninger)

Frittstående siden topicen ikke er provisjonert og det ikke finnes produsenter → både Kotlin-navn
og `@SerialName` står fritt nå. Konvensjon (som B57/B59): engelsk for strukturelt/teknisk, norsk for
NAV-kanoniske domeneord.

| Nå | Frys til |
|---|---|
| `ExternalVarsling`/`externalVarsling` | `EksternVarsling`/`eksternVarsling` (B29) |
| felt `smsText`/`emailTitle`/`emailText`/`channels` | `smsTekst`/`epostTittel`/`epostTekst`/`kanaler` (B29) |
| `ExternalChannel` | `EksternKanal` (verdier `SMS`/`EMAIL` beholdes) |
| `Tag`/`tag` | `Merkelapp`/`merkelapp` (låst B30) |
| `@SerialName("referanse")`, `@SerialName("mottaker")` | droppes (wire = Kotlin-navn) |

Beholdes: `sykmeldt`, `orgnummer`, `NarmesteLeder`, `journalpostId`, `Sakstilknytning`/`sakId`,
`Varseltype`, `ArbeidsgiverMeldingstype`, `DistributionType`, `SendingWindow`, `PersonIdentifier`,
`BrevFallback` («fallback» = innarbeidet norsk lånord, «brev» = domeneanker), `text`/`link`/`visibleUntil`.

### 4. Avhengigheter

`:kontrakt` avhenger kun av `api(kotlinx-serialization-json)` (transitivt nødvendig for konsumentene)
+ stdlib. Ingen `kotlinx-datetime` (bruker stdlib `kotlin.time.Instant`; kotlinx-serialization 1.11 har
innebygd Instant-serializer). `UuidSerializer` fjernes (død kode etter at eventId flyttet til header).

## Konsekvenser

- Produsentene får autocomplete + kompileringstid-feil + én ergonomisk vei fra «beskriv melding» til
  «klar for Kafka», uten å arve vår kafka-clients-versjon.
- 11 top-level DSL-funksjoner (én per variant) — flat, oppdagbar overflate.
- Navnefrysen er en engangs-brytende endring på interne kallssteder (12 filer) — gjøres nå mens det
  er gratis; låses ved `1.0.0`.

## Alternativer vurdert

- **Ferdig `ProducerRecord`-factory / send-klient (L4):** forkastet — bryter B54 («ingen
  producer-klient») og tvinger vår kafka-clients-versjon på konsumentene.
- **Klassisk builder (mutable + valider i `.build()`):** forkastet — flytter required-sjekk fra
  kompileringstid til runtime, en regresjon fra B21/B22.
- **Samlet `dispatch(reference) { pickVariant() }`-inngang:** forkastet — gjeninnfører «valgte du
  nøyaktig én?»-runtime-sjekken.
- **Frys navn som-er (behold språkmiks):** forkastet — låser `ExternalVarsling`-miksen ved `1.0.0`.
- **UUID v7 for eventId:** forkastet — krever opt-in/ekstra avhengighet; tidssortering var et argument
  for budstikkas interne DB-genererte `delivery.id` (B44), ikke for produsent-eventId.
