SET LOCAL lock_timeout = '5s';

-- Match the application's inbox-before-delivery lock order.
LOCK TABLE inbox_message IN ACCESS EXCLUSIVE MODE;
ALTER TABLE delivery
    ADD CONSTRAINT delivery_snapshot_kalenderavtale_check CHECK (
        channel <> 'KALENDERAVTALE'
        OR (
            snapshot_state = 'RESOLVED'
            AND (
                payload -> 'mottaker' ->> 'type' IS DISTINCT FROM 'NarmesteLeder'
                OR NOT (
                    operation = 'CREATE'
                    OR (payload -> 'mottaker' -> 'externalVarsling' IS NOT NULL
                        AND payload -> 'mottaker' -> 'externalVarsling' <> 'null'::jsonb)
                    OR (payload -> 'saksstatus' IS NOT NULL AND payload -> 'saksstatus' <> 'null'::jsonb)
                )
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
            )
        )
    );
