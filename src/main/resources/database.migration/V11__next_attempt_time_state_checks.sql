-- Do not queue the ADD CONSTRAINT lock behind a long transaction and block traffic.
SET LOCAL lock_timeout = '5s';

-- Lock inbox_message before delivery, matching the application's write order, to avoid deadlocks.
ALTER TABLE inbox_message
    ADD CONSTRAINT inbox_message_wait_next_attempt_time_check
    CHECK (state <> 'WAIT' OR next_attempt_time IS NOT NULL);

ALTER TABLE inbox_message
    ADD CONSTRAINT inbox_message_claimed_next_attempt_time_check
    CHECK (state <> 'CLAIMED' OR next_attempt_time IS NOT NULL);

ALTER TABLE delivery
    ADD CONSTRAINT delivery_claimed_next_attempt_time_check
    CHECK (state <> 'CLAIMED' OR next_attempt_time IS NOT NULL);
