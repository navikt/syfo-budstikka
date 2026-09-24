-- Do not queue the ADD CONSTRAINT lock behind a long transaction and block traffic.
SET LOCAL lock_timeout = '5s';

-- Lock inbox_message before delivery, matching the application's write order, to avoid deadlocks.
ALTER TABLE inbox_message
    ADD CONSTRAINT inbox_message_claim_token_state_check
    CHECK ((state = 'CLAIMED') = (claim_token IS NOT NULL));

ALTER TABLE delivery
    ADD CONSTRAINT delivery_claim_token_state_check
    CHECK ((state = 'CLAIMED') = (claim_token IS NOT NULL));
