-- leveranse: outbox-rader (frosne konkrete utsendinger) som beslutnings-workeren skriver.
-- Denne migreringen dekker det beslutnings-workeren (#20/#56) trenger å skrive; outbox-worker-
-- kolonner (tidligst_sending, frist_tid, sendt_tid, ekstern_respons_id) legges additivt til i #21.
-- FK inbox_event_id -> ON DELETE SET NULL (B42: inbox_hendelse slettes før leveranse ved retensjon).
CREATE TABLE delivery
(
    id                UUID        NOT NULL DEFAULT uuidv7(),
    inbox_event_id    UUID        REFERENCES inbox_message (event_id) ON DELETE SET NULL,
    reference         TEXT        NOT NULL,
    operation         TEXT        NOT NULL,
    channel           TEXT        NOT NULL,
    recipient_type     TEXT        NOT NULL,
    recipient_id       TEXT        NOT NULL,
    payload           JSONB       NOT NULL,
    state             TEXT        NOT NULL DEFAULT 'READY',
    attempt           INT         NOT NULL DEFAULT 0,
    next_attempt_time TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_message     TEXT,
    CONSTRAINT delivery_pkey PRIMARY KEY (id)
);

-- Plukk-indeks for outbox-worker (state='READY' AND next_attempt_time <= now()).
CREATE INDEX ON delivery (state, next_attempt_time);
-- Oppslag på hvilke leveranser en inbox-hendelse produserte.
CREATE INDEX ON delivery (inbox_event_id);
