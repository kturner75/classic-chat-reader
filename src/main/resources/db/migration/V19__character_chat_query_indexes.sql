-- Complete the owner-and-character history index so it also covers the repository's
-- updated_at DESC, created_at DESC ordering without changing the checksum of the
-- already-released V17 migration.
CREATE INDEX idx_ccc_user_character_activity
    ON character_chat_conversations (user_id, character_id, updated_at, created_at);
