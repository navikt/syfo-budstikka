-- Scheduled microfrontend hiding (ADR 0018, #282).
-- Do not queue the locks behind a long transaction and block traffic.
SET LOCAL lock_timeout = '5s';

-- Set only on rows Budstikka derives from another inbox row (a scheduled disable). It names the
-- source, whose (received_at, event_id) is the derived row's place in ingest order. No foreign key:
-- retention may delete a terminal source before its derived row fires. The column is additive and
-- nullable, so older code ignores it and wakes the row as an ordinary disable.
ALTER TABLE inbox_message
    ADD COLUMN source_event_id UUID;

-- Looks up pending and newer microfrontend rows per (sykmeldt, microfrontendId). Only microfrontend
-- content carries microfrontendId, so the index covers those rows only.
CREATE INDEX inbox_message_microfrontend_key_idx
    ON inbox_message ((content ->> 'personIdentifier'), (content ->> 'microfrontendId'))
    WHERE (content ->> 'microfrontendId') IS NOT NULL;
