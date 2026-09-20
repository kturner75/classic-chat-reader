package com.classicchatreader.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds one account's data across every user-owned table, plus shared book, chapter, character,
 * class, and term rows. Used by account export and deletion tests.
 */
public final class AccountDataFixture {

    private AccountDataFixture() {
    }

    /** Shared rows: a book, chapter, character, class section owned by {@code teacherId}, and a term. */
    public static void seedShared(JdbcTemplate jdbc, String teacherId) {
        user(jdbc, teacherId);
        jdbc.update("INSERT INTO books (id, source, source_id, title, author) VALUES ('fx-book', 'gutenberg', 'fx-1342', 'Pride and Prejudice', 'Austen, Jane')");
        jdbc.update("INSERT INTO chapters (id, book_id, chapter_index, title) VALUES ('fx-ch', 'fx-book', 0, 'Chapter I')");
        jdbc.update("INSERT INTO characters (id, book_id, character_type, created_at, first_chapter_id, first_paragraph_index, name, retry_count, status) "
                + "VALUES ('fx-darcy', 'fx-book', 'PRIMARY', CURRENT_TIMESTAMP, 'fx-ch', 0, 'Mr. Darcy', 0, 'COMPLETED')");
        jdbc.update("INSERT INTO class_sections (id, owner_user_id, name, status, created_at, updated_at) VALUES ('fx-class', ?, 'English 101', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", teacherId);
        jdbc.update("INSERT INTO terms (id, class_section_id, name, status, start_date, end_date, created_at, updated_at) "
                + "VALUES ('fx-term', 'fx-class', 'Fall', 'ACTIVE', DATE '2026-08-24', DATE '2026-12-12', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO assignments (id, term_id, title, book_id, status, sort_order, created_by_user_id, created_at, updated_at) "
                + "VALUES ('fx-assignment', 'fx-term', 'Read Chapter I', 'fx-book', 'PUBLISHED', 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", teacherId);
    }

    public static void user(JdbcTemplate jdbc, String userId) {
        jdbc.update("INSERT INTO users (id, email, created_at, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", userId, userId + "@example.test");
    }

    /** A student with data in every user-owned table; {@code tag} makes content distinguishable. */
    public static void seedStudent(JdbcTemplate jdbc, String userId, String tag) {
        user(jdbc, userId);
        jdbc.update("INSERT INTO user_local_credentials (user_id, password_hash, failed_login_attempts, created_at, updated_at) VALUES (?, '$2a$10$secret-hash-" + tag + "', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", userId);
        jdbc.update("INSERT INTO user_sessions (id, user_id, token_hash, created_at, expires_at, last_accessed_at) VALUES (?, ?, 'token-" + tag + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "sess-" + tag, userId);
        jdbc.update("INSERT INTO user_reader_states (user_id, state_json, updated_at) VALUES (?, '{\"favoriteBookIds\":[\"fx-book\"],\"tag\":\"" + tag + "\"}', CURRENT_TIMESTAMP)", userId);
        jdbc.update("INSERT INTO paragraph_annotations (id, reader_id, user_id, book_id, chapter_id, paragraph_index, highlighted, note_text, bookmarked, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'fx-book', 'fx-ch', 3, TRUE, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "ann-" + tag, "reader-" + tag, userId, "note " + tag);
        jdbc.update("INSERT INTO quiz_attempts (id, chapter_id, user_id, correct_answers, total_questions, score_percent, perfect, difficulty_level, created_at) "
                + "VALUES (?, 'fx-ch', ?, 4, 5, 80, FALSE, 1, CURRENT_TIMESTAMP)", "qa-" + tag, userId);
        jdbc.update("INSERT INTO quiz_trophies (id, book_id, user_id, code, title, description, unlocked_at) VALUES (?, 'fx-book', ?, ?, 'First quiz', 'Took a quiz', CURRENT_TIMESTAMP)", "tr-" + tag, userId, "first-" + tag);
        jdbc.update("INSERT INTO character_chat_conversations (id, user_id, character_id, created_at, updated_at) VALUES (?, ?, 'fx-darcy', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "conv-" + tag, userId);
        jdbc.update("INSERT INTO character_chat_messages (id, conversation_id, user_id, sequence_number, role, content, created_at) VALUES (?, ?, ?, 0, 'USER', ?, CURRENT_TIMESTAMP)", "ccm-" + tag, "conv-" + tag, userId, "character chat " + tag);
        jdbc.update("INSERT INTO reading_buddy_messages (id, owner_key, book_id, persona_id, role, content, kind, chapter_index, paragraph_index, content_hash, created_at, chronology_sequence) "
                + "VALUES (?, ?, 'fx-book', 'sage', 'user', ?, 'chat', 0, 0, 'h', CURRENT_TIMESTAMP, 1)", "rbm-" + tag, "user:" + userId, "buddy chat " + tag);
        jdbc.update("INSERT INTO reading_buddy_memories (id, owner_key, book_id, persona_id, summary_text, summary_version, updated_at) VALUES (?, ?, 'fx-book', 'sage', ?, 1, CURRENT_TIMESTAMP)", "rbmem-" + tag, "user:" + userId, "memory " + tag);
        jdbc.update("INSERT INTO reading_buddy_preferences (id, owner_key, book_id, enabled, frequency, created_at, updated_at) VALUES (?, ?, 'fx-book', TRUE, 'rare', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "rbp-" + tag, "user:" + userId);
        jdbc.update("INSERT INTO enrollments (id, term_id, user_id, role, status, joined_date, created_at, updated_at) VALUES (?, 'fx-term', ?, 'STUDENT', 'ACTIVE', DATE '2026-08-24', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "enr-" + tag, userId);
        jdbc.update("INSERT INTO assignment_progress (id, term_id, assignment_id, user_id, first_opened_at, created_at, updated_at) VALUES (?, 'fx-term', 'fx-assignment', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "ap-" + tag, userId);
        jdbc.update("INSERT INTO account_capabilities (id, user_id, capability, status, granted_at, updated_at) VALUES (?, ?, 'CREATE_CLASSROOM', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "cap-" + tag, userId);
        jdbc.update("INSERT INTO classroom_usage_events (id, user_id, term_id, event_type, book_id, duration_ms, occurred_at, created_at) VALUES (?, ?, 'fx-term', 'READING_HEARTBEAT', 'fx-book', 60000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "cue-" + tag, userId);
    }

    /** Teacher-side rows for {@code teacherId}: a class role, settings, quiz, override, invite, and school membership. */
    public static void seedTeacherContent(JdbcTemplate jdbc, String teacherId) {
        jdbc.update("INSERT INTO class_role_memberships (id, term_id, user_id, role, status, created_at, updated_at) VALUES ('fx-crm', 'fx-term', ?, 'TEACHER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", teacherId);
        jdbc.update("INSERT INTO class_feature_settings (term_id, updated_at, updated_by_user_id) VALUES ('fx-term', CURRENT_TIMESTAMP, ?)", teacherId);
        jdbc.update("INSERT INTO assignment_chapters (id, assignment_id, chapter_id, chapter_index, sort_order) VALUES ('fx-ac', 'fx-assignment', 'fx-ch', 0, 0)");
        jdbc.update("INSERT INTO assignment_quizzes (id, assignment_id, payload_json, created_by_user_id, created_at, updated_at) VALUES ('fx-aq', 'fx-assignment', '{}', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", teacherId);
        jdbc.update("INSERT INTO quiz_question_overrides (id, term_id, book_id, chapter_id, operation, overlay_key, sort_order, question_json, status, created_by_user_id, created_at, updated_at) "
                + "VALUES ('fx-qqo', 'fx-term', 'fx-book', 'fx-ch', 'ADD', 'fx-q', 0, '{}', 'ACTIVE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", teacherId);
        jdbc.update("INSERT INTO invite_links (id, term_id, code_hash, created_by_user_id, created_at, updated_at) VALUES ('fx-il', 'fx-term', 'fx-hash', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", teacherId);
        jdbc.update("INSERT INTO schools (id, name, status, created_at, updated_at) VALUES ('fx-school', 'Columbia State', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO school_memberships (id, school_id, user_id, role, status, created_at, updated_at) VALUES ('fx-sm', 'fx-school', ?, 'TEACHER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", teacherId);
    }
}
