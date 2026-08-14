-- Reset one student's progress on one assignment so they can re-test the flow.
-- Local testing only — do not run against production.
--
-- Always clears assignment-scoped rows:
--   assignment_progress, quiz_attempts, classroom_usage_events
-- Optionally also clears book-scoped reading / character-chat state (see flags).
--
-- IntelliJ Database:
--   1. Edit the params block below.
--   2. Run the whole file (green arrow / Run), not Execute Statement on one query.
--   3. Inspect the preview result grids. A dry run ends with an exception on
--      purpose so the writes roll back; set dry_run to false and re-run to apply.
--
-- Find IDs first with scripts/sql/lookup_classroom.sql.
-- Browser localStorage may still show reading position until the reader re-syncs.

DROP TABLE IF EXISTS _sql_params;
CREATE TEMP TABLE _sql_params AS
SELECT
    'PASTE_ASSIGNMENT_ID'::varchar AS assignment_id,
    'student@example.com'::varchar AS student_email,
    true AS dry_run,
    true AS reset_book_activity,
    false AS reset_character_chat;

-- Preview: resolved assignment + student
SELECT p.assignment_id,
       p.student_email,
       p.dry_run,
       p.reset_book_activity,
       p.reset_character_chat,
       a.title AS assignment_title,
       a.book_id,
       a.term_id,
       a.character_chat_required,
       u.id AS user_id,
       u.email
FROM _sql_params p
LEFT JOIN assignments a ON a.id = p.assignment_id
LEFT JOIN users u ON lower(u.email) = lower(p.student_email);

-- Preview: rows that would be removed
SELECT 'assignment_progress' AS kind, COUNT(*) AS rows
FROM assignment_progress ap
JOIN _sql_params p ON p.assignment_id = ap.assignment_id
JOIN users u ON u.id = ap.user_id AND lower(u.email) = lower(p.student_email)
UNION ALL
SELECT 'quiz_attempts', COUNT(*)
FROM quiz_attempts qa
JOIN _sql_params p ON p.assignment_id = qa.assignment_id
JOIN users u ON u.id = qa.user_id AND lower(u.email) = lower(p.student_email)
UNION ALL
SELECT 'classroom_usage_events', COUNT(*)
FROM classroom_usage_events e
JOIN _sql_params p ON p.assignment_id = e.assignment_id
JOIN users u ON u.id = e.user_id AND lower(u.email) = lower(p.student_email)
UNION ALL
SELECT 'user_reader_states.bookActivity (book-scoped)',
       CASE WHEN p.reset_book_activity THEN
           (SELECT COUNT(*)
            FROM user_reader_states urs
            JOIN assignments a ON a.id = p.assignment_id
            JOIN users u ON lower(u.email) = lower(p.student_email)
            WHERE urs.user_id = u.id
              AND urs.state_json IS NOT NULL
              AND jsonb_exists(urs.state_json::jsonb -> 'bookActivity', a.book_id))
       ELSE 0 END
FROM _sql_params p
UNION ALL
SELECT 'character_chat_conversations (book-scoped)',
       CASE WHEN p.reset_character_chat THEN
           (SELECT COUNT(*)
            FROM character_chat_conversations c
            JOIN characters ch ON ch.id = c.character_id
            JOIN assignments a ON a.id = p.assignment_id
            JOIN users u ON lower(u.email) = lower(p.student_email)
            WHERE c.user_id = u.id
              AND ch.book_id = a.book_id)
       ELSE 0 END
FROM _sql_params p;

DO $body$
DECLARE
    p _sql_params%ROWTYPE;
    v_user_id varchar;
    v_book_id varchar;
    n integer;
BEGIN
    SELECT * INTO STRICT p FROM _sql_params;

    SELECT COUNT(*) INTO n
    FROM assignments a
    JOIN users u ON lower(u.email) = lower(p.student_email)
    WHERE a.id = p.assignment_id;
    IF n <> 1 THEN
        RAISE EXCEPTION
            'Expected exactly one assignment+student match, found %. Check assignment_id and student_email.',
            n;
    END IF;

    SELECT u.id, a.book_id
    INTO STRICT v_user_id, v_book_id
    FROM assignments a
    JOIN users u ON lower(u.email) = lower(p.student_email)
    WHERE a.id = p.assignment_id;

    DELETE FROM assignment_progress
    WHERE assignment_id = p.assignment_id
      AND user_id = v_user_id;

    DELETE FROM quiz_attempts
    WHERE assignment_id = p.assignment_id
      AND user_id = v_user_id;

    DELETE FROM classroom_usage_events
    WHERE assignment_id = p.assignment_id
      AND user_id = v_user_id;

    IF p.reset_book_activity THEN
        UPDATE user_reader_states
        SET state_json = jsonb_set(
                    state_json::jsonb,
                    '{bookActivity}',
                    COALESCE(state_json::jsonb -> 'bookActivity', '{}'::jsonb) - v_book_id
                )::text,
            updated_at = timezone('utc', now())
        WHERE user_id = v_user_id
          AND state_json IS NOT NULL
          AND jsonb_typeof(state_json::jsonb) = 'object';
    END IF;

    IF p.reset_character_chat THEN
        DELETE FROM character_chat_conversations c
        USING characters ch
        WHERE c.user_id = v_user_id
          AND c.character_id = ch.id
          AND ch.book_id = v_book_id;
    END IF;

    IF p.dry_run THEN
        RAISE EXCEPTION 'Dry run — rolled back. Set dry_run to false in _sql_params and re-run to apply.';
    END IF;

    RAISE NOTICE 'Reset assignment % for %', p.assignment_id, p.student_email;
END
$body$;
