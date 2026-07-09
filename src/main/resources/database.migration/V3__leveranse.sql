-- leveranse: outbox-rader (frosne konkrete utsendinger) som beslutnings-workeren skriver.
-- Denne migreringen dekker det beslutnings-workeren (#20/#56) trenger å skrive; outbox-worker-
-- kolonner (tidligst_sending, frist_tid, sendt_tid, ekstern_respons_id) legges additivt til i #21.
-- FK inbox_event_id -> ON DELETE SET NULL (B42: inbox_hendelse slettes før leveranse ved retensjon).
CREATE TABLE leveranse
(
    id                UUID        NOT NULL DEFAULT uuidv7(),
    inbox_event_id    UUID        REFERENCES inbox_formidling (event_id) ON DELETE SET NULL,
    referanse         TEXT        NOT NULL,
    operasjon         TEXT        NOT NULL,
    kanal             TEXT        NOT NULL,
    mottaker_type     TEXT        NOT NULL,
    mottaker_id       TEXT        NOT NULL,
    payload           JSONB       NOT NULL,
    state             TEXT        NOT NULL DEFAULT 'KLAR',
    attempt           INT         NOT NULL DEFAULT 0,
    next_attempt_time TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_message     TEXT,
    CONSTRAINT leveranse_pkey PRIMARY KEY (id)
);

-- Plukk-indeks for outbox-worker (state='KLAR' AND next_attempt_time <= now()).
CREATE INDEX ON leveranse (state, next_attempt_time);
-- Oppslag på hvilke leveranser en inbox-hendelse produserte.
CREATE INDEX ON leveranse (inbox_event_id);
