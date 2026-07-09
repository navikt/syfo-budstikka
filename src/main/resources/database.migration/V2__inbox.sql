CREATE TABLE inbox_formidling
(
    event_id          UUID        NOT NULL DEFAULT  uuidv7(),
    payload           TEXT        NOT NULL,
    state             TEXT        NOT NULL DEFAULT 'RECEIVED',
    drop_reason       TEXT,
    attempt           INT         NOT NULL DEFAULT 0,
    next_attempt_time TIMESTAMPTZ,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at      TIMESTAMPTZ,
    error_message     TEXT,
    CONSTRAINT inbox_formidling_pkey PRIMARY KEY (event_id)
);

CREATE INDEX ON inbox_formidling (state, next_attempt_time);

-- Dead-letter for meldinger konsumenten ikke klarte å parse ved konsument-grensen.
-- payload er text/bytea — ALDRI jsonb: tabellen finnes for å bevare mulig-ugyldige bytes.
-- kafka_offset er Kafka-koordinaten for etterforskning/replay ("offset" er reservert ord i SQL).
CREATE TABLE dead_letter_formidling
(
    id             UUID        NOT NULL DEFAULT uuidv7(),
    payload        TEXT        NOT NULL,
    topic          TEXT        NOT NULL,
    partition      INT         NOT NULL,
    kafka_offset   BIGINT      NOT NULL,
    kafka_key      TEXT,
    failure_reason TEXT        NOT NULL,
    error_message  TEXT,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT dead_letter_formidling_pkey PRIMARY KEY (id)
);

CREATE INDEX ON dead_letter_formidling (received_at);
