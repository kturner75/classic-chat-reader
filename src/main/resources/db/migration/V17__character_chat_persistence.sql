-- BL-049: account-owned character conversation threads and ordered transcript messages.
-- Portable DDL for H2, MariaDB/MySQL, and PostgreSQL.

CREATE TABLE character_chat_conversations (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    character_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    -- Enables the composite message FK to enforce that message ownership matches thread ownership.
    CONSTRAINT uk_ccc_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_ccc_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_ccc_character FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE
);

-- Supports loading one user's threads for a character and choosing the most recently active thread.
CREATE INDEX idx_ccc_user_character_updated
    ON character_chat_conversations (user_id, character_id, updated_at);

-- Supports the cross-character recent-thread list used by My Chats.
CREATE INDEX idx_ccc_user_updated
    ON character_chat_conversations (user_id, updated_at);

CREATE TABLE character_chat_messages (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    -- Optional stable id supplied by a client/claim-sync operation for idempotent retries.
    client_message_id VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ccm_conversation_sequence UNIQUE (conversation_id, sequence_number),
    CONSTRAINT uk_ccm_conversation_client_message UNIQUE (conversation_id, client_message_id),
    CONSTRAINT fk_ccm_conversation_owner FOREIGN KEY (conversation_id, user_id)
        REFERENCES character_chat_conversations (id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_ccm_sequence_nonnegative CHECK (sequence_number >= 0),
    CONSTRAINT chk_ccm_role CHECK (role IN ('USER', 'CHARACTER', 'SYSTEM'))
);

-- Covers owner-scoped transcript reads in deterministic creation order.
CREATE INDEX idx_ccm_conversation_user_sequence
    ON character_chat_messages (conversation_id, user_id, sequence_number);
