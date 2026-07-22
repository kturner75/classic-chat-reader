-- Explicit operator rollback for V18. Flyway Community does not run undo migrations automatically.
ALTER TABLE character_chat_conversations DROP COLUMN context_paragraph_index;
ALTER TABLE character_chat_conversations DROP COLUMN context_chapter_title;
ALTER TABLE character_chat_conversations DROP COLUMN context_chapter_index;
ALTER TABLE character_chat_conversations DROP COLUMN context_chapter_id;
