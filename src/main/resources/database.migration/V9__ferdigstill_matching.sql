-- Indexes for FERDIGSTILL matching and stable Fager external id storage.
CREATE INDEX inbox_message_reference_idx
    ON inbox_message (reference);

CREATE INDEX delivery_ferdigstill_match_idx
    ON delivery (reference, operation, channel, recipient_type, recipient_id, created_at, id);

ALTER TABLE delivery
    ADD COLUMN create_external_id TEXT;

-- Freeze the original Fager external id only when it is still recoverable from the CREATE row's
-- inbox event id. Historical rows where retention already nulled the foreign key remain invalid
-- stored CREATE rows for FERDIGSTILL and must not fabricate a fallback from delivery.id.
UPDATE delivery
SET create_external_id = inbox_event_id::text
WHERE operation = 'CREATE'
  AND channel = 'ARBEIDSGIVERVARSEL'
  AND inbox_event_id IS NOT NULL;
