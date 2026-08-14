-- Hard-delete one assignment and related rows.
-- Local testing only — do not run against production.
--
-- The app only soft-deletes assignments (deleted_at). This script removes the
-- row so you can recreate it from scratch during local testing.
--
-- Child / related tables (FK or assignment_id):
--   assignment_chapters, assignment_quizzes, assignment_progress,
--   quiz_attempts, classroom_usage_events
--
-- IntelliJ Database:
--   1. Edit assignment_id and dry_run in the params block below.
--   2. Run the whole file (green arrow / Run), not Execute Statement on one query.
--   3. Inspect the preview result grids. A dry run ends with an exception on
--      purpose so the deletes roll back; set dry_run to false and re-run to apply.
--
-- Find IDs first with scripts/sql/lookup_classroom.sql.

DROP TABLE IF EXISTS _sql_params;
CREATE TEMP TABLE _sql_params AS
SELECT
    'PASTE_ASSIGNMENT_ID'::varchar AS assignment_id,
    true AS dry_run;

-- Preview: target assignment
SELECT p.assignment_id,
       p.dry_run,
       a.title,
       a.status,
       a.deleted_at,
       a.book_id,
       a.term_id,
       t.name AS term_name
FROM _sql_params p
LEFT JOIN assignments a ON a.id = p.assignment_id
LEFT JOIN terms t ON t.id = a.term_id;

-- Preview: rows that would be removed
SELECT 'quiz_attempts' AS kind, COUNT(*) AS rows
FROM quiz_attempts qa
JOIN _sql_params p ON p.assignment_id = qa.assignment_id
UNION ALL
SELECT 'classroom_usage_events', COUNT(*)
FROM classroom_usage_events e
JOIN _sql_params p ON p.assignment_id = e.assignment_id
UNION ALL
SELECT 'assignment_progress', COUNT(*)
FROM assignment_progress ap
JOIN _sql_params p ON p.assignment_id = ap.assignment_id
UNION ALL
SELECT 'assignment_quizzes', COUNT(*)
FROM assignment_quizzes aq
JOIN _sql_params p ON p.assignment_id = aq.assignment_id
UNION ALL
SELECT 'assignment_chapters', COUNT(*)
FROM assignment_chapters ac
JOIN _sql_params p ON p.assignment_id = ac.assignment_id
UNION ALL
SELECT 'assignments', COUNT(*)
FROM assignments a
JOIN _sql_params p ON p.assignment_id = a.id;

DO $body$
DECLARE
    p _sql_params%ROWTYPE;
    n integer;
BEGIN
    SELECT * INTO STRICT p FROM _sql_params;

    SELECT COUNT(*) INTO n FROM assignments WHERE id = p.assignment_id;
    IF n <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one assignment, found %. Check assignment_id.', n;
    END IF;

    DELETE FROM quiz_attempts WHERE assignment_id = p.assignment_id;
    DELETE FROM classroom_usage_events WHERE assignment_id = p.assignment_id;
    DELETE FROM assignment_progress WHERE assignment_id = p.assignment_id;
    DELETE FROM assignment_quizzes WHERE assignment_id = p.assignment_id;
    DELETE FROM assignment_chapters WHERE assignment_id = p.assignment_id;
    DELETE FROM assignments WHERE id = p.assignment_id;

    IF p.dry_run THEN
        RAISE EXCEPTION 'Dry run — rolled back. Set dry_run to false in _sql_params and re-run to apply.';
    END IF;

    RAISE NOTICE 'Deleted assignment %', p.assignment_id;
END
$body$;
