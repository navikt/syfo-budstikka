-- Install the compatibility trigger before repairing. CREATE TRIGGER obtains ShareRowExclusiveLock
-- on delivery, which blocks legacy INSERTs until this transaction's repair is committed; no writer
-- can slip between the backfill and trigger boundary. An id::text value with no inbox identity is
-- V9's ambiguous fallback, not evidence of the originally published external id, and must fail
-- closed.
CREATE FUNCTION set_arbeidsgivervarsel_create_external_id()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.operation = 'CREATE'
       AND NEW.channel = 'ARBEIDSGIVERVARSEL'
       AND NEW.create_external_id IS NULL
       AND NEW.inbox_event_id IS NOT NULL THEN
        NEW.create_external_id := NEW.inbox_event_id::text;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER delivery_set_arbeidsgivervarsel_create_external_id
BEFORE INSERT ON delivery
FOR EACH ROW
EXECUTE FUNCTION set_arbeidsgivervarsel_create_external_id();

UPDATE delivery
SET create_external_id = inbox_event_id::text
WHERE operation = 'CREATE'
  AND channel = 'ARBEIDSGIVERVARSEL'
  AND create_external_id IS NULL
  AND inbox_event_id IS NOT NULL;

UPDATE delivery
SET create_external_id = NULL
WHERE operation = 'CREATE'
  AND channel = 'ARBEIDSGIVERVARSEL'
  AND inbox_event_id IS NULL
  AND create_external_id = id::text;

-- Pending dependent closes inherit only a repaired source identity. Terminal rows retain their
-- recorded outcome, attempts, and external-id value.
UPDATE delivery AS dependent
SET create_external_id = source.create_external_id
FROM delivery AS source
WHERE dependent.operation = 'INACTIVATE'
  AND dependent.channel = 'ARBEIDSGIVERVARSEL'
  AND dependent.state NOT IN ('SENT', 'FAILED')
  AND dependent.source_create_delivery_id = source.id
  AND source.operation = 'CREATE'
  AND source.channel = 'ARBEIDSGIVERVARSEL'
  AND (
      dependent.create_external_id IS NULL
      OR dependent.create_external_id = source.id::text
  );
