-- V4__hydrer_inbox.sql
-- ADR 0008 / B61: hydrert inbox — full parse ved ingest.
--
-- eventId lever nå KUN i Kafka-headeren (DispatchHeader.EVENT_ID); payloaden parses ved inntak og
-- inbox-raden hydreres. `content` lagres som jsonb (speiler `delivery.payload`), og `reference`
-- løftes ut som egen kolonne (selektiv FERDIGSTILL-match-nøkkel B39 + eneste konvolutt-felt utenfor
-- `content`). Rå `payload text` droppes. Indeks på `reference` legges IKKE til her — den venter på
-- hold-plassering (DECISIONS #1); kolonnen finnes fra nå (ADR 0008).
--
-- TRYGT som rene ALTER-er uten backfill/default: tjenesten er pre-prod og topicet konsumeres ikke
-- ennå → `inbox_message` er tom, så NOT NULL uten default er ufarlig. Skulle tjenesten vært i prod
-- måtte kolonnene backfilles før NOT NULL settes.

ALTER TABLE inbox_message
    ADD COLUMN content   JSONB NOT NULL,
    ADD COLUMN reference TEXT  NOT NULL,
    DROP COLUMN payload;

-- Best-effort korrelasjon (ADR 0008 pkt. 3): en payload-parse-feil med gyldig header dead-letteres
-- MED event_id; mangler/ugyldig header er den null. Ny nullable kolonne.
ALTER TABLE dead_letter_message
    ADD COLUMN event_id UUID;
