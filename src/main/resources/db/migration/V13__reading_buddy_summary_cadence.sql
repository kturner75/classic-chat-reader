-- Durable cadence baseline for rolling summary: message count right after last successful summary
-- (after fold-delete / hard-cap prune). Used so refresh interval stays summary-every-messages
-- even when absolute total resets to recentMessages after pruning folded rows.

ALTER TABLE reading_buddy_memories
    ADD COLUMN messages_at_last_summary INTEGER NOT NULL DEFAULT 0;
