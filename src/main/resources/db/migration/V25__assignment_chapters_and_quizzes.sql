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

ALTER TABLE quiz_attempts
    ADD COLUMN assignment_id VARCHAR(255) NULL;

ALTER TABLE quiz_attempts
    ALTER COLUMN chapter_id DROP NOT NULL;

ALTER TABLE quiz_attempts
    ADD CONSTRAINT ck_qa_chapter_or_assignment
        CHECK (chapter_id IS NOT NULL OR assignment_id IS NOT NULL);

CREATE INDEX idx_qa_assignment_user_created
    ON quiz_attempts (assignment_id, user_id, created_at);
