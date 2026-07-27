UPDATE delivery
SET attempt       = 0,
    error_message = NULL
WHERE state = 'FAILED';

UPDATE inbox_message
SET attempt       = 0,
    error_message = NULL
WHERE state = 'FAILED';
