-- Remember which TTS provider chose the saved book voice so a later
-- provider switch re-runs analysis instead of reusing leftover ids.
-- The UPDATE is a one-time backfill of rows chosen while OpenAI served TTS;
-- runtime re-choose logic uses stored provider + current catalog, not this list.

ALTER TABLE books ADD COLUMN tts_voice_provider VARCHAR(32);

UPDATE books
SET tts_voice_provider = 'openai'
WHERE tts_voice IS NOT NULL
  AND lower(tts_voice) IN (
      'alloy', 'ash', 'ballad', 'cedar', 'coral', 'echo',
      'fable', 'marin', 'nova', 'onyx', 'sage', 'shimmer', 'verse'
  );
