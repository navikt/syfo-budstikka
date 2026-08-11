-- Indeks for FERDIGSTILL-oppslag mot ventende OPPRETT-er og frosset Fager-eksternId for lukking.
CREATE INDEX inbox_message_reference_idx ON inbox_message (reference);

CREATE INDEX delivery_ferdigstill_match_idx
    ON delivery (reference, operation, channel, recipient_type, recipient_id, created_at, id);

ALTER TABLE delivery
    ADD COLUMN create_external_id TEXT;

-- Fager's external id belongs to the original ARBEIDSGIVERVARSEL CREATE delivery. Freeze it
-- before inbox retention can null out the foreign key.
-- A legacy CREATE whose inbox FK is already null cannot recover the originally used inbox event
-- id. COALESCE(inbox_event_id, id) is the best available backfill; no retention job exists today.
UPDATE delivery
SET create_external_id = COALESCE(inbox_event_id, id)::text
WHERE operation = 'CREATE'
  AND channel = 'ARBEIDSGIVERVARSEL';
