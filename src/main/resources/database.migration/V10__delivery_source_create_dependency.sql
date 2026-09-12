-- A derived INACTIVATE must keep the exact CREATE that supplied its payload and external id.
-- RESTRICT deliberately preserves a SENT source while its dependent is nonterminal: retention may
-- not delete or null the relationship and thereby make the close independently dispatchable.
ALTER TABLE delivery
    ADD COLUMN source_create_delivery_id UUID
        REFERENCES delivery (id) ON DELETE RESTRICT;

CREATE INDEX delivery_source_create_delivery_id_idx
    ON delivery (source_create_delivery_id);
