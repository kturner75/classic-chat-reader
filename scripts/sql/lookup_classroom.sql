-- Local testing helper: list classroom assignments and roster emails.
-- Do not run against production.
--
-- IntelliJ Database: run the whole file (green arrow / Run) to get one result
-- grid per query. Optional email filter is in the last query.

-- Terms
SELECT t.id AS term_id,
       t.name AS term_name,
       t.status AS term_status,
       cs.name AS class_name,
       cs.id AS class_section_id
FROM terms t
JOIN class_sections cs ON cs.id = t.class_section_id
WHERE t.deleted_at IS NULL
  AND cs.deleted_at IS NULL
ORDER BY cs.name, t.name;

-- Assignments
SELECT a.id AS assignment_id,
       a.title,
       a.status,
       a.deleted_at,
       a.quiz_required,
       a.quiz_source,
       a.character_chat_required,
       a.book_id,
       b.title AS book_title,
       t.name AS term_name,
       a.term_id,
       (SELECT COUNT(*) FROM assignment_chapters ac WHERE ac.assignment_id = a.id) AS chapter_count,
       (SELECT COUNT(*) FROM assignment_progress ap WHERE ap.assignment_id = a.id) AS opened_count,
       (SELECT COUNT(*) FROM quiz_attempts qa WHERE qa.assignment_id = a.id) AS quiz_attempt_count
FROM assignments a
JOIN terms t ON t.id = a.term_id
LEFT JOIN books b ON b.id = a.book_id
ORDER BY t.name, a.sort_order, a.created_at;

-- Roster (active enrollments)
SELECT u.email,
       u.id AS user_id,
       e.role,
       e.status AS enrollment_status,
       e.display_name_override,
       t.name AS term_name,
       t.id AS term_id
FROM enrollments e
JOIN users u ON u.id = e.user_id
JOIN terms t ON t.id = e.term_id
WHERE e.deleted_at IS NULL
ORDER BY t.name, e.role, u.email;

-- Student assignment status (opened / quiz attempts)
-- Optional: uncomment the email line to filter to one student.
SELECT u.email,
       a.title AS assignment_title,
       a.id AS assignment_id,
       ap.first_opened_at,
       (SELECT COUNT(*)
        FROM quiz_attempts qa
        WHERE qa.assignment_id = a.id
          AND qa.user_id = u.id) AS quiz_attempts
FROM enrollments e
JOIN users u ON u.id = e.user_id
JOIN assignments a ON a.term_id = e.term_id AND a.deleted_at IS NULL
LEFT JOIN assignment_progress ap ON ap.assignment_id = a.id AND ap.user_id = u.id
WHERE e.deleted_at IS NULL
  AND e.role = 'STUDENT'
  AND e.status = 'ACTIVE'
  -- AND lower(u.email) = lower('student@example.com')
ORDER BY u.email, a.sort_order, a.created_at;
