-- Manual rollback for V17. Run only after backing up any character chat data.
-- Flyway Community does not execute undo migrations automatically.
DROP TABLE character_chat_messages;
DROP TABLE character_chat_conversations;
