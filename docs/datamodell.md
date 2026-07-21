# Datamodell — syfo-budstikka

Datamodellen følger dagens kode. Kilden er `InboxMessageTable`, `DeliveryTable` og
`DeadLetterMessageTable` i `src/main/kotlin/no/nav/budstikka/infrastructure/database/`.

## Tabeller

```mermaid
erDiagram
    inbox_message ||--o{ delivery : "0..N"

    inbox_message {
        uuid        event_id PK
        text        reference
        text        recipient_type
        text        recipient_id
        text        channel
        text        operation
        jsonb       content
        text        state "RECEIVED|CLAIMED|PROCESSED|DROPPED|FAILED"
        text        drop_reason "nullable"
        int         attempt
        timestamptz next_attempt_time "nullable"
        timestamptz received_at
        timestamptz processed_at "nullable"
        text        error_message "nullable"
    }

    delivery {
        uuid        id PK
        uuid        inbox_event_id "nullable FK-lenke"
        text        reference
        text        operation
        text        channel
        text        recipient_type
        text        recipient_id
        jsonb       payload
        text        state "READY|CLAIMED|SENT|FAILED"
        int         attempt
        timestamptz next_attempt_time "nullable"
        timestamptz created_at
        text        error_message "nullable"
    }

    dead_letter_message {
        uuid        id PK
        text        payload
        text        topic
        int         partition
        bigint      kafka_offset
        text        kafka_key "nullable"
        uuid        event_id "nullable (best-effort header)"
        text        failure_reason
        text        error_message "nullable"
        timestamptz received_at
    }
```

## Inbox og dead letter

- Konsumenten **parser hele `Dispatch` ved ingest** (ADR 0008, superseder ADR 0002) og
  hydrerer `inbox_message`: dedup på **header-eventId** (`DispatchHeader.EVENT_ID`) som PK,
  strukturerte kolonner (`reference`/`recipient_type`/`recipient_id`/`channel`/`operation`)
  løftes ut, og `content` lagres som `jsonb`. Strukturerte felt gjør at FERDIGSTILL kan
  matche/annullere ennå-ubesluttede inbox-rader uten re-parsing (#27).
- `eventId` lever **kun** i Kafka-headeren (fjernet fra payloaden, `Dispatch = { reference,
  content }`); headeren er autoritativ og obligatorisk. Best-effort lagres eventId også på
  `dead_letter_message` (`event_id`) for korrelasjon når en melding dead-letteres.
- Melding som ikke kan behandles ved inntak (manglende/ugyldig header, tom payload, korrupt
  JSON, konvolutt uten `reference`, parser-urepresenterbar content) skrives til
  `dead_letter_message`; offset committes. En *representable-men-ulovlig* kombinasjon (B21)
  dead-letteres IKKE — den når inbox og håndteres av beslutnings-workeren.
- **Retensjon (B42 + ADR 0008):** `inbox_message` og `dead_letter_message` slettes hardt
  ved alder > ~100 dager (≥ 90d replay-vindu, B26, + buffer); DL bærer rå payload m/fnr og
  må ha samme slette-disiplin.

## Worker-flyt og state-overganger

### `inbox_message.state`

```text
RECEIVED -> CLAIMED -> PROCESSED
                   -> DROPPED
                   -> FAILED

CLAIMED -> CLAIMED (lease utløpt, kan re-claimes)
```

- Claim bruker `FOR UPDATE SKIP LOCKED` og lease via `next_attempt_time`.
- `attempt` økes ved claim.
- Terminal overgang (`PROCESSED`/`DROPPED`/`FAILED`) er compare-and-set fra `CLAIMED`.

### `delivery.state`

```text
READY -> CLAIMED -> SENT
                -> FAILED

CLAIMED -> CLAIMED (handler kaster, lease utløpt, kan re-claimes)
```

- Delivery-worker claimer bare kanaler den har `ChannelHandler` for.
- `markSent` og `markFailed` er compare-and-set fra `CLAIMED`.
- `attempt` økes ved claim.

## Indekser

- `inbox_message_state_next_attempt_time_idx` på `(state, next_attempt_time)`
- `delivery_state_next_attempt_time_idx` på `(state, next_attempt_time)`
- `delivery_inbox_event_id_idx` på `(inbox_event_id)`
- `dead_letter_message_received_at_idx` på `(received_at)`

## Observability-koblinger

- Primær korrelasjon er `eventId`.
- For delivery brukes også `delivery.id` for sporing av ett konkret sendeforsøk.
- Metrikklabels holdes lavkardinale; detaljer går i logger/traces.
