ALTER TABLE reading_buddy_messages
    ADD COLUMN chronology_sequence BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_rbm_owner_book_persona_chronology
    ON reading_buddy_messages (
        owner_key,
        book_id,
        persona_id,
        created_at,
        chronology_sequence,
        id
    );
