-- Existing unassigned attempts are pre-assignment recap history (V25 leftovers).
-- New /chapter/{id}/grade rows stay false so they cannot consume assignment retries.
ALTER TABLE quiz_attempts
    ADD COLUMN legacy_unassigned BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE quiz_attempts
SET legacy_unassigned = TRUE
WHERE assignment_id IS NULL;
