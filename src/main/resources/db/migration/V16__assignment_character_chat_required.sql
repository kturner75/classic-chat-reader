-- BL-025.11 Slice B: optional character-chat requirement on assignments (show-and-tell).
ALTER TABLE assignments
    ADD COLUMN character_chat_required BOOLEAN NOT NULL DEFAULT FALSE;
