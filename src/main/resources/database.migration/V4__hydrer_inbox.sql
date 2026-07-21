-- V4__hydrer_inbox.sql
-- Hydrert inbox — full parse ved ingest (ADR 0008).
-- NOT NULL uten default er trygt fordi tjenesten er pre-prod og topicet ikke konsumeres ennå
-- (inbox_message er tom). I prod måtte kolonnene backfilles før NOT NULL settes.

-- slette alle rader siden vi er bare i dev
TRUNCATE TABLE delivery;
TRUNCATE TABLE inbox_message;
TRUNCATE TABLE dead_letter_message;

ALTER TABLE inbox_message
    ADD COLUMN content   JSONB NOT NULL,
    ADD COLUMN reference TEXT  NOT NULL,
    DROP COLUMN payload;

-- event_id nullable: settes best-effort når Kafka-headeren var gyldig, ellers null.
ALTER TABLE dead_letter_message
    ADD COLUMN event_id UUID;
