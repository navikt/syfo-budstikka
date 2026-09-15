-- Indeks for FERDIGSTILL-oppslag mot ventende OPPRETT-er og lagret ekstern identitet.
CREATE INDEX inbox_message_reference_idx ON inbox_message (reference);

CREATE INDEX delivery_ferdigstill_match_idx
    ON delivery (reference, operation, channel, recipient_type, recipient_id, created_at, id);

ALTER TABLE delivery
    ADD COLUMN external_id TEXT;
