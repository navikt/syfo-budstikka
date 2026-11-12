SET LOCAL lock_timeout = '5s';

-- Match the inbox-before-delivery lock order used by retention and materialization.
LOCK TABLE inbox_message IN ACCESS EXCLUSIVE MODE;
ALTER TABLE delivery ADD COLUMN notification_id TEXT;

-- Existing notifications used the inbox id. A never-attempted orphan can still choose its original
-- delivery-id fallback before its first call. An orphan that may have been sent stays unknown.
UPDATE delivery
SET notification_id = CASE
    WHEN inbox_event_id IS NOT NULL THEN inbox_event_id::text
    WHEN attempt = 0 AND state IN ('READY', 'CLAIMED') THEN id::text
END
WHERE channel = 'ARBEIDSGIVERVARSEL' AND operation = 'CREATE';
