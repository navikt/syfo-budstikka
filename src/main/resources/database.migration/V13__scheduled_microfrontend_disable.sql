-- Scheduled microfrontend hiding.
-- Do not queue the locks behind a long transaction and block traffic.
SET LOCAL lock_timeout = '5s';

-- Set only on rows Budstikka derives from another inbox row (a scheduled disable). It names the
-- source, whose (received_at, event_id) is the derived row's place in ingest order. No foreign key:
-- a processed derived row and its source are deleted independently, in either order. The column is
-- additive and nullable, so older code ignores it and wakes the row as an ordinary disable.
ALTER TABLE inbox_message
    ADD COLUMN source_event_id UUID;

-- Retention keeps a source while a row derived from it is pending, and finds those rows here.
CREATE INDEX inbox_message_source_event_id_idx
    ON inbox_message (source_event_id)
    WHERE source_event_id IS NOT NULL;

-- Looks up pending and newer microfrontend rows per (sykmeldt, microfrontendId). Only microfrontend
-- content carries microfrontendId, so the index covers those rows only.
CREATE INDEX inbox_message_microfrontend_key_idx
    ON inbox_message ((content ->> 'personIdentifier'), (content ->> 'microfrontendId'))
    WHERE (content ->> 'microfrontendId') IS NOT NULL;
