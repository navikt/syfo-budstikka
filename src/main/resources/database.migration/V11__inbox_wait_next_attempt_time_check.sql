-- Do not queue the ADD CONSTRAINT lock behind a long transaction and block traffic.
SET LOCAL lock_timeout = '5s';

ALTER TABLE inbox_message
    ADD CONSTRAINT inbox_message_wait_next_attempt_time_check
    CHECK (state <> 'WAIT' OR next_attempt_time IS NOT NULL);
