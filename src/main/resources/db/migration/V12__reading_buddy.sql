-- Reading Buddy Mode: preferences, messages, and rolling memory.
-- Portable DDL for H2, MariaDB/MySQL, and PostgreSQL (no partial indexes).

CREATE TABLE reading_buddy_preferences (
    id VARCHAR(255) PRIMARY KEY,
    owner_key VARCHAR(120) NOT NULL,
    -- Real book id, or '__global__' for the single global prefs row per owner
    book_id VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    frequency VARCHAR(32) NOT NULL DEFAULT 'rare',
    default_persona_id VARCHAR(64) NULL,
    persona_id VARCHAR(64) NULL,
    suppress_until TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_rbp_frequency CHECK (frequency IN ('rare', 'occasional', 'chatty'))
);

-- One row per owner+book_id including exactly one '__global__' row per owner
CREATE UNIQUE INDEX uk_rbp_owner_book
    ON reading_buddy_preferences (owner_key, book_id);

CREATE INDEX idx_rbp_owner ON reading_buddy_preferences (owner_key);

CREATE TABLE reading_buddy_messages (
    id VARCHAR(255) PRIMARY KEY,
    owner_key VARCHAR(120) NOT NULL,
    book_id VARCHAR(255) NOT NULL,
    persona_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    chapter_index INTEGER NOT NULL,
    paragraph_index INTEGER NOT NULL,
    -- Set only for kind='proactive': '{chapterIndex}:{paragraphIndex}'; NULL for chat/other
    proactive_position_key VARCHAR(64) NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_rbm_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    CONSTRAINT chk_rbm_role CHECK (role IN ('buddy', 'user', 'system')),
    CONSTRAINT chk_rbm_kind CHECK (kind IN ('proactive', 'chat', 'summary_marker'))
);

CREATE INDEX idx_rbm_owner_book_persona_created
    ON reading_buddy_messages (owner_key, book_id, persona_id, created_at);

-- Portable proactive uniqueness: multiple NULLs allowed in unique key on MariaDB/Postgres/H2
-- for chat rows; proactive rows must set proactive_position_key non-null
CREATE UNIQUE INDEX uk_rbm_proactive_position
    ON reading_buddy_messages (owner_key, book_id, persona_id, proactive_position_key);

CREATE TABLE reading_buddy_memories (
    id VARCHAR(255) PRIMARY KEY,
    owner_key VARCHAR(120) NOT NULL,
    book_id VARCHAR(255) NOT NULL,
    persona_id VARCHAR(64) NOT NULL,
    summary_text TEXT NOT NULL,
    summary_version INTEGER NOT NULL DEFAULT 0,
    -- Watermark: max message position folded into summary_text; used to omit summary on rewind
    summary_max_chapter_index INTEGER NULL,
    summary_max_paragraph_index INTEGER NULL,
    last_message_id VARCHAR(255) NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_rbmem_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_rbmem_owner_book_persona
    ON reading_buddy_memories (owner_key, book_id, persona_id);
