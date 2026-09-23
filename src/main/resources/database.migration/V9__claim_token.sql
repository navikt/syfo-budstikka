-- Nullable claim tokens allow old and new workers to coexist during a rolling deploy.
ALTER TABLE delivery ADD COLUMN claim_token UUID;
ALTER TABLE inbox_message ADD COLUMN claim_token UUID;
