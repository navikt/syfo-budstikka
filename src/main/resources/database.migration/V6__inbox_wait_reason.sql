-- V6__inbox_wait_reason.sql
-- Egen ventårsak-kolonne for sendevindu-hold (ADR 0014). Tidligere ble error_message
-- misbrukt til å bære ventårsaken; det blander en ikke-feil (venting) med reelle feil.
-- Additiv og nullable → kolonne-nivå rollback-trygg (gammel kode ignorerer kolonnen).
ALTER TABLE inbox_message
    ADD COLUMN wait_reason TEXT;
