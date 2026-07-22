-- BL-050: retain the reader location used to resume an account-owned conversation.
-- Nullable columns keep conversations created by V17 readable; the service falls back to
-- the character's first chapter when an older row has no captured reader context.

ALTER TABLE character_chat_conversations ADD COLUMN context_chapter_id VARCHAR(255) NULL;
ALTER TABLE character_chat_conversations ADD COLUMN context_chapter_index INTEGER NULL;
ALTER TABLE character_chat_conversations ADD COLUMN context_chapter_title VARCHAR(255) NULL;
ALTER TABLE character_chat_conversations ADD COLUMN context_paragraph_index INTEGER NULL;
