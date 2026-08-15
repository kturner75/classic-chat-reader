-- Cover subject is cover-only. Do not store it in illustration_prompt_prefix
-- or chapter illustrations and portraits inherit "manor as sole focal subject".

ALTER TABLE books ADD COLUMN illustration_cover_subject VARCHAR(32);
ALTER TABLE books ADD COLUMN illustration_cover_focus VARCHAR(500);
