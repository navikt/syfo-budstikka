CREATE TABLE inbox_hendelse (
    event_id        UUID        NOT NULL,
    referanse       TEXT        NOT NULL,
    payload         TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'MOTTATT',
    drop_aarsak     TEXT,
    forsok          INT         NOT NULL DEFAULT 0,
    neste_forsok_tid TIMESTAMPTZ,
    mottatt_tid     TIMESTAMPTZ NOT NULL DEFAULT now(),
    behandlet_tid   TIMESTAMPTZ,
    feilmelding     TEXT,
    CONSTRAINT inbox_hendelse_pkey PRIMARY KEY (event_id)
);

CREATE INDEX ON inbox_hendelse (status, neste_forsok_tid);

-- Dead-letter for meldinger konsumenten ikke klarte å parse ved konsument-grensen.
-- payload er text/bytea — ALDRI jsonb: tabellen finnes for å bevare mulig-ugyldige bytes.
-- kafka_offset er Kafka-koordinaten for etterforskning/replay ("offset" er reservert ord i SQL).
CREATE TABLE inbox_feilet (
    id              BIGSERIAL   NOT NULL,
    payload         TEXT        NOT NULL,
    topic           TEXT        NOT NULL,
    partisjon       INT         NOT NULL,
    kafka_offset    BIGINT      NOT NULL,
    kafka_key       TEXT,
    feilaarsak      TEXT        NOT NULL,
    feilmelding     TEXT,
    mottatt_tid     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT inbox_feilet_pkey PRIMARY KEY (id)
);

CREATE INDEX ON inbox_feilet (mottatt_tid);
