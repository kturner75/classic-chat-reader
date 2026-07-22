-- Account-owned character chat history used by My Chats.
-- Parent records are intentionally RESTRICTed: snapshots allow safe unavailable rendering,
-- while catalog hard-delete must not orphan retained account data.

CREATE TABLE character_chat_sessions (
    id VARCHAR(255) PRIMARY KEY,
    owner_user_id VARCHAR(255) NOT NULL,
    book_id VARCHAR(255) NOT NULL,
    character_id VARCHAR(255) NOT NULL,
    book_title_snapshot VARCHAR(255) NOT NULL,
    book_author_snapshot VARCHAR(255) NOT NULL,
    character_name_snapshot VARCHAR(255) NOT NULL,
    portrait_available_snapshot BOOLEAN NOT NULL DEFAULT FALSE,
    context_chapter_id VARCHAR(255) NOT NULL,
    context_chapter_index INTEGER NOT NULL,
    context_chapter_title VARCHAR(255) NOT NULL,
    context_paragraph_index INTEGER NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    last_message_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_character_chat_owner_book_character
        UNIQUE (owner_user_id, book_id, character_id),
    CONSTRAINT fk_character_chat_owner FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT fk_character_chat_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_character_chat_character FOREIGN KEY (character_id) REFERENCES characters (id),
    CONSTRAINT fk_character_chat_context_chapter FOREIGN KEY (context_chapter_id) REFERENCES chapters (id)
);

CREATE INDEX idx_character_chat_owner_recent
    ON character_chat_sessions (owner_user_id, deleted, last_message_at, id);
CREATE INDEX idx_character_chat_owner_book_recent
    ON character_chat_sessions (owner_user_id, book_id, last_message_at);
CREATE INDEX idx_character_chat_owner_character_recent
    ON character_chat_sessions (owner_user_id, character_id, last_message_at);

CREATE TABLE character_chat_messages (
    id VARCHAR(255) PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_character_chat_message_session
        FOREIGN KEY (session_id) REFERENCES character_chat_sessions (id) ON DELETE CASCADE,
    CONSTRAINT chk_character_chat_message_role CHECK (role IN ('USER', 'CHARACTER'))
);

CREATE INDEX idx_character_chat_message_session_created
    ON character_chat_messages (session_id, created_at, id);
