-- These indexes support bounded retention cleanup.
-- Ordinary transactional index creation is intentional for the current low-traffic rollout.
CREATE INDEX inbox_message_received_at_event_id_idx
    ON inbox_message (received_at, event_id);

-- This composite index supersedes the single-column index and is created before it is dropped.
CREATE INDEX dead_letter_message_received_at_id_idx
    ON dead_letter_message (received_at, id);

DROP INDEX dead_letter_message_received_at_idx;

CREATE INDEX delivery_created_at_id_sent_failed_idx
    ON delivery (created_at, id)
    WHERE state IN ('SENT', 'FAILED');
