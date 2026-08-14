-- Multi-chapter assignments and assignment-scoped quizzes.

CREATE TABLE assignment_chapters (
    id VARCHAR(255) PRIMARY KEY,
    assignment_id VARCHAR(255) NOT NULL,
    chapter_id VARCHAR(255) NOT NULL,
    chapter_index INT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_ac_assignment_chapter UNIQUE (assignment_id, chapter_id),
    CONSTRAINT fk_ac_assignment FOREIGN KEY (assignment_id) REFERENCES assignments (id),
    CONSTRAINT fk_ac_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id)
);

CREATE INDEX idx_ac_assignment ON assignment_chapters (assignment_id);
CREATE INDEX idx_ac_chapter ON assignment_chapters (chapter_id);

INSERT INTO assignment_chapters (id, assignment_id, chapter_id, chapter_index, sort_order)
SELECT CONCAT('ac-', a.id),
       a.id,
       a.chapter_id,
       COALESCE(a.chapter_index, 0),
       0
FROM assignments a
WHERE a.chapter_id IS NOT NULL
  AND a.chapter_id <> '';

ALTER TABLE assignments
    ADD COLUMN quiz_source VARCHAR(16) NULL;

UPDATE assignments
SET quiz_source = 'CHAPTER'
WHERE quiz_required = TRUE
  AND chapter_id IS NOT NULL
  AND chapter_id <> '';

-- Attach existing chapter attempts to the matching quiz assignment before the
-- legacy assignments.chapter_id column is dropped. Runtime policy/overview
-- queries by assignment_id only.
ALTER TABLE quiz_attempts
    ADD COLUMN assignment_id VARCHAR(255) NULL;

-- Attach a legacy chapter attempt only when exactly one quiz-required assignment
-- matches that chapter. Multiple same-chapter assignments leave assignment_id
-- null so every CHAPTER assignment can still see the shared history.
UPDATE quiz_attempts
SET assignment_id = (
    SELECT a.id
    FROM assignments a
    LEFT JOIN enrollments e
      ON e.term_id = a.term_id
     AND e.user_id = quiz_attempts.user_id
     AND e.deleted_at IS NULL
     AND e.status = 'ACTIVE'
    WHERE a.chapter_id = quiz_attempts.chapter_id
      AND a.quiz_required = TRUE
      AND a.deleted_at IS NULL
    ORDER BY CASE WHEN e.user_id IS NOT NULL THEN 0 ELSE 1 END,
             CASE WHEN a.status = 'PUBLISHED' THEN 0 ELSE 1 END,
             a.created_at
    LIMIT 1
)
WHERE assignment_id IS NULL
  AND chapter_id IS NOT NULL
  AND (
    SELECT COUNT(*)
    FROM assignments a2
    WHERE a2.chapter_id = quiz_attempts.chapter_id
      AND a2.quiz_required = TRUE
      AND a2.deleted_at IS NULL
  ) = 1;

ALTER TABLE assignments DROP COLUMN chapter_id;
ALTER TABLE assignments DROP COLUMN chapter_index;

CREATE TABLE assignment_quizzes (
    id VARCHAR(255) PRIMARY KEY,
    assignment_id VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    created_by_user_id VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_aq_assignment UNIQUE (assignment_id),
    CONSTRAINT fk_aq_assignment FOREIGN KEY (assignment_id) REFERENCES assignments (id),
    CONSTRAINT fk_aq_creator FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);

-- chapter_id nullability is dialect-specific (PostgreSQL vs MariaDB) and is
-- applied in V27__quiz_attempts_chapter_id_nullable.

ALTER TABLE quiz_attempts
    ADD CONSTRAINT ck_qa_chapter_or_assignment
        CHECK (chapter_id IS NOT NULL OR assignment_id IS NOT NULL);

CREATE INDEX idx_qa_assignment_user_created
    ON quiz_attempts (assignment_id, user_id, created_at);
