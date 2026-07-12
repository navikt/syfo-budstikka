# 0002: Inbox-dedup på Kafka-header, rå payload uten deserialisering

- Status: besluttet (implementerer B54, issue #19)
- Dato: 2026-07-08
- Relatert: ADR 0001 (domeneblind varselruter), beslutning B54 og B26 i `docs/context.md`

## Kontekst

Budstikka konsumerer `team-esyfo.formidling.v1` og må dedup-e idempotent (B4) fordi
Kafka-replay (bounded 90d retention, B26) kan dobbeltsende. Spørsmålet var *hvor*
dedup-nøkkelen og feilhåndteringen forankres:

- `eventId` ligger både i payload-konvolutten (autoritativ, B4/B43) og speiles som
  Kafka-header (`DispatchHeader.EVENT_ID`, kontraktkonstant fra #18).
- Å parse bodyen ved ingest binder dedup til payload-skjemaet: en ikke-relatert
  schema-endring eller en `UGYLDIG_JSON` ville da kunne blokkere selve dedup-en.
- En Kafka-consumer må skille feil som skal **retryes** (transient) fra feil som
  aldri blir bedre (poison) — ellers får du enten stille datatap eller
  head-of-line blocking på partisjonen.

## Beslutning

Ingest gjør **null body-parsing**. Konkret:

1. **`eventId` leses fra Kafka-headeren**, ikke fra bodyen, som dedup-nøkkel
   (fast-path per B54). Payloaden forblir autoritativ og valideres av
   beslutnings-workeren senere (`payload.eventId == header.eventId`).
2. **Rå payload lagres byte-eksakt** som `text` i `inbox_message` uten
   deserialisering. Dedup løsrives fra skjemaet; `UGYLDIG_JSON`-triggeren ved
   ingest forsvinner.
3. **`referanse` deferres til workeren** (B54 lot dette stå åpent for #19). Ingest
   trenger den ikke; den leses strukturelt først når innholdet dekodes.
4. **Dedup via `event_id` som PK** (`insertIgnore` / ON CONFLICT DO NOTHING).
5. **Feiltaksonomi:**
   - *Poison* (manglende/ugyldig `event_id`-header, tom payload): dead-letteres til
     egen tabell (`DeadLetterMessageTable`), og offset committes → partisjonen
     flyter videre.
   - *Transient* (DB nede): unntak kastes → ConsumerRunner committer ikke og
     re-poller med backoff.

## Konsekvenser

- ➕ Dedup og ingest overlever payload-skjemaendringer — bodyen røres ikke før den
  må dekodes.
- ➕ Poison-meldinger blokkerer aldri partisjonen; transient-feil taper aldri data
  stille. Skillet er strukturelt, ikke ad hoc.
- ➕ Dead-letter er isolert i eget repository/tabell, adskilt fra inbox-persistering.
- ➖ Sannheten finnes to steder (header + body) → workeren MÅ verifisere
  `payload.eventId == header.eventId`; avvik er poison. Uten den sjekken kan en
  produsent-bug (header ≠ body) gi feil dedup-nøkkel.
- ➖ En produsent som utelater headeren dead-letteres (`MISSING_EVENT_ID`) selv om
  bodyen er gyldig — «obligatorisk header» er en kontrakt, ikke en broker-garanti.
- 🔒 Replay er kun trygt så lenge inbox-dedup-radene (`event_id`) beholdes ≥ 90d
  (B26/B42-gulv) — ellers gir replay dobbeltvarsling.

## Alternativer vurdert

- **Dedup på payload-`eventId` (parse ved ingest).** Vraket: binder dedup til
  skjemaet og gjeninnfører `UGYLDIG_JSON` som ingest-feilflate — nettopp det B54
  fjerner.
- **Dead-letter alt (også transient) og alltid committe offset.** Vraket: transient
  DB-feil ville gitt stille datatap i stedet for retry.
- **Kast på poison og la consumer stoppe.** Vraket: head-of-line blocking — én
  korrupt melding stopper hele partisjonen.
