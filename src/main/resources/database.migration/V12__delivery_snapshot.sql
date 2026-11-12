SET LOCAL lock_timeout = '5s';

-- Match the application's inbox-before-delivery lock order.
LOCK TABLE inbox_message IN ACCESS EXCLUSIVE MODE;
ALTER TABLE delivery
    ADD COLUMN snapshot_state TEXT NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN leader_fnr TEXT,
    ADD COLUMN leader_emails JSONB,
    ADD COLUMN leader_relation_id UUID,
    ADD COLUMN name_first TEXT,
    ADD COLUMN name_middle TEXT,
    ADD COLUMN name_last TEXT,
    ADD CONSTRAINT delivery_snapshot_state_check CHECK (snapshot_state IN ('LEGACY', 'RESOLVED')),
    ADD CONSTRAINT delivery_snapshot_name_check CHECK (
        (name_first IS NULL AND name_middle IS NULL AND name_last IS NULL)
        OR (name_first IS NOT NULL AND name_last IS NOT NULL)
    ),
    ADD CONSTRAINT delivery_snapshot_leader_check CHECK (
        snapshot_state = 'LEGACY'
        OR channel <> 'ARBEIDSGIVERVARSEL'
        OR operation <> 'CREATE'
        OR payload -> 'mottaker' ->> 'type' IS DISTINCT FROM 'NarmesteLeder'
        OR (
            leader_fnr IS NOT NULL AND length(leader_fnr) = 11
            AND leader_relation_id IS NOT NULL
            AND leader_emails IS NOT NULL
            AND CASE WHEN jsonb_typeof(leader_emails) = 'array' THEN
                CASE WHEN payload -> 'mottaker' -> 'externalVarsling' IS NOT NULL
                          AND payload -> 'mottaker' -> 'externalVarsling' <> 'null'::jsonb
                     THEN jsonb_array_length(leader_emails) > 0
                     ELSE TRUE END
                ELSE FALSE END
        )
    );
