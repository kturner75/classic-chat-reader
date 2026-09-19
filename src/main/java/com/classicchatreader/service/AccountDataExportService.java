package com.classicchatreader.service;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * "Download my data" for a signed-in account (BL-043.6). The export-first notice before account
 * deletion points here. Covers what the account created or accrued: reader state, annotations,
 * quizzes, character chats, Reading Buddy, and the account's own classroom records, both as a
 * student (enrollments, progress, usage) and as a teacher (class roles, owned classes).
 *
 * <p>Rows are streamed from a cursor (fetch size 500) into a private temporary file inside one
 * read-only, repeatable-read transaction, so a long history never has to fit in memory, the file is
 * one consistent snapshot, and the database connection is released before the (possibly slow)
 * download starts. File lifetime and concurrency live in {@link AccountExportFiles}.
 *
 * <p>Explicit column lists only: credentials, sessions, sign-in identities, capability grants,
 * internal reader ids, and other people's data are never included. Every column of every exported
 * table is either exported or deliberately omitted; {@code AccountDataExportColumnPolicyTest} holds
 * the omission list with reasons and fails when a new column is neither. Soft-deleted rows the account
 * still owns are included (with their {@code deleted_at}); they are held data until purged.
 */
@Service
public class AccountDataExportService {

    private static final int FETCH_SIZE = 500;
    /** Exports above this size are refused (413) rather than filling the temp volume. */
    static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

    public static class ExportTooLargeException extends RuntimeException {
        public ExportTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * The teacher's classwork: assignments they created, or whose custom quiz they last edited
     * (editing rewrites assignment_quizzes.created_by_user_id). Assignments, their chapters, and
     * their quizzes are all selected through this one set, so a quiz always ships with its assignment.
     */
    private static final String MY_ASSIGNMENTS =
            "(a.created_by_user_id = :u OR a.id IN (SELECT mq.assignment_id FROM assignment_quizzes mq WHERE mq.created_by_user_id = :u))";

    /** Assignments a student's own records point at: opened (progress) or attempted (quiz attempts). */
    private static final String STUDENT_ASSIGNMENTS =
            "SELECT assignment_id FROM assignment_progress WHERE user_id = :u "
                    + "UNION SELECT assignment_id FROM quiz_attempts WHERE user_id = :u AND assignment_id IS NOT NULL";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final ObjectMapper json = new ObjectMapper();

    public AccountDataExportService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(FETCH_SIZE);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        // One snapshot for the whole file: a Reading Buddy summarization or assignment edit mid-export
        // cannot pair old rows with new ones (PostgreSQL default READ COMMITTED would allow it).
        this.readOnly.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public boolean accountExists(String userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = :u", new MapSqlParameterSource("u", userId), Integer.class);
        return n != null && n > 0;
    }

    /** Whole export in memory; for tests and small callers. The HTTP endpoint streams via {@link #writeExport}. */
    public byte[] export(String userId) {
        if (!accountExists(userId)) {
            throw new IllegalArgumentException("Account not found");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeExport(userId, out);
        return out.toByteArray();
    }

    /**
     * Writes the export into the lease's owner-only file and returns it. The lease (see
     * {@link AccountExportFiles}) owns the file: closing it, or the download stream it opens, deletes
     * the file. Throws {@link ExportTooLargeException} beyond {@code maxBytes}; the caller closes the lease.
     */
    public java.nio.file.Path writeExportFile(String userId, AccountExportFiles.Lease lease, long maxBytes) throws IOException {
        java.nio.file.Path file = lease.createFile();
        // The lease's stream heartbeats on every write, so a long preparation is never reclaimed as idle.
        try (OutputStream out = new CappedOutputStream(new java.io.BufferedOutputStream(lease.openForWriting()), maxBytes)) {
            writeExport(userId, out);
        }
        return file;
    }

    public java.nio.file.Path writeExportFile(String userId, AccountExportFiles.Lease lease) throws IOException {
        return writeExportFile(userId, lease, DEFAULT_MAX_BYTES);
    }

    private static final class CappedOutputStream extends java.io.FilterOutputStream {
        private final long max;
        private long written;

        CappedOutputStream(OutputStream out, long max) {
            super(out);
            this.max = max;
        }

        private void count(long n) {
            written += n;
            if (written > max) {
                throw new ExportTooLargeException("This account's data is too large to download here. Contact support for a bulk export.");
            }
        }

        @Override
        public void write(int b) throws IOException {
            count(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count(len);
            out.write(b, off, len);
        }
    }

    public void writeExport(String userId, OutputStream out) {
        readOnly.executeWithoutResult(status -> {
            // The caller owns the stream (e.g. the servlet response); closing the generator only flushes it.
            try (JsonGenerator g = json.getFactory().createGenerator(out, JsonEncoding.UTF8)
                    .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
                g.useDefaultPrettyPrinter();
                write(g, new MapSqlParameterSource("u", userId).addValue("k", "user:" + userId));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void write(JsonGenerator g, MapSqlParameterSource user) throws IOException {
        g.writeStartObject();
        g.writeStringField("exportedAt", Instant.now().toString());
        g.writeStringField("timestampNote", "Row timestamps are server times as stored, without a time zone.");
        g.writeFieldName("account");
        object(g, "SELECT id, email, created_at, updated_at FROM users WHERE id = :u", user);
        g.writeFieldName("readerState");
        object(g, "SELECT state_json, updated_at FROM user_reader_states WHERE user_id = :u", user);
        array(g, "annotations", """
                SELECT a.book_id, b.title AS book_title, a.chapter_id, a.paragraph_index, a.highlighted,
                       a.note_text, a.bookmarked, a.created_at, a.updated_at
                FROM paragraph_annotations a LEFT JOIN books b ON b.id = a.book_id
                WHERE a.user_id = :u ORDER BY a.updated_at, a.id""", user);
        array(g, "quizAttempts", """
                SELECT COALESCE(c.book_id, qa.book_id) AS book_id, q.chapter_id, c.title AS chapter_title, q.assignment_id,
                       qa.title AS assignment_title,
                       q.legacy_unassigned, q.correct_answers, q.total_questions, q.score_percent, q.perfect, q.difficulty_level, q.created_at
                FROM quiz_attempts q LEFT JOIN chapters c ON c.id = q.chapter_id LEFT JOIN assignments qa ON qa.id = q.assignment_id
                WHERE q.user_id = :u ORDER BY q.created_at, q.id""", user);
        array(g, "quizTrophies", """
                SELECT t.book_id, b.title AS book_title, t.code, t.title, t.description, t.unlocked_at
                FROM quiz_trophies t LEFT JOIN books b ON b.id = t.book_id
                WHERE t.user_id = :u ORDER BY t.unlocked_at, t.id""", user);
        characterChats(g, user);
        g.writeObjectFieldStart("readingBuddy");
        array(g, "messages", """
                SELECT book_id, persona_id, role, kind, content, chapter_index, paragraph_index, proactive_position_key, created_at
                FROM reading_buddy_messages WHERE owner_key = :k ORDER BY created_at, chronology_sequence, id""", user);
        array(g, "memories", """
                SELECT book_id, persona_id, summary_text, summary_version,
                       summary_max_chapter_index, summary_max_paragraph_index, messages_at_last_summary, updated_at
                FROM reading_buddy_memories WHERE owner_key = :k ORDER BY updated_at, id""", user);
        array(g, "preferences", """
                SELECT book_id, enabled, frequency, default_persona_id, persona_id, suppress_until, created_at, updated_at
                FROM reading_buddy_preferences WHERE owner_key = :k ORDER BY updated_at, id""", user);
        g.writeEndObject();
        g.writeObjectFieldStart("classroom");
        array(g, "enrollments", """
                SELECT e.term_id, t.name AS term_name, s.name AS class_name, e.role, e.status,
                       e.joined_date, e.left_date, e.display_name_override, e.created_at, e.updated_at, e.deleted_at
                FROM enrollments e JOIN terms t ON t.id = e.term_id LEFT JOIN class_sections s ON s.id = t.class_section_id
                WHERE e.user_id = :u ORDER BY e.joined_date, e.id""", user);
        // Each progress row carries the assignment's descriptive context: a student has no copy of the
        // assignment elsewhere in the file, and an opaque id is meaningless outside this database.
        array(g, "assignmentProgress", """
                SELECT p.term_id, p.assignment_id, a.title AS assignment_title, a.book_id, b.title AS book_title,
                       a.due_date, a.available_from_date, a.quiz_required, a.character_chat_required, a.status AS assignment_status,
                       p.first_opened_at, p.created_at, p.updated_at
                FROM assignment_progress p LEFT JOIN assignments a ON a.id = p.assignment_id LEFT JOIN books b ON b.id = a.book_id
                WHERE p.user_id = :u ORDER BY p.first_opened_at, p.id""", user);
        // Every assignment the student's progress or quiz attempts refer to, described once, whether or
        // not a progress row exists (grading can record an attempt without one).
        array(g, "assignments", """
                SELECT a.id AS assignment_id, a.title, s.name AS class_name, t.name AS term_name, a.term_id, a.book_id,
                       b.title AS book_title, a.due_date, a.available_from_date, a.quiz_required, a.character_chat_required,
                       a.status, a.deleted_at
                FROM assignments a LEFT JOIN terms t ON t.id = a.term_id LEFT JOIN class_sections s ON s.id = t.class_section_id
                LEFT JOIN books b ON b.id = a.book_id
                WHERE a.id IN (""" + STUDENT_ASSIGNMENTS + ") ORDER BY a.created_at, a.id", user);
        array(g, "assignmentChapters", """
                SELECT c.assignment_id, c.chapter_id, ch.title AS chapter_title, c.chapter_index, c.sort_order
                FROM assignment_chapters c LEFT JOIN chapters ch ON ch.id = c.chapter_id
                WHERE c.assignment_id IN (""" + STUDENT_ASSIGNMENTS + ") ORDER BY c.assignment_id, c.sort_order, c.id", user);
        array(g, "usageEvents", """
                SELECT term_id, class_section_id, school_id, event_type, book_id, chapter_id, paragraph_index, assignment_id,
                       duration_ms, progress_percent, feature, provider, model_name, input_tokens, output_tokens,
                       estimated_cost_micros, metadata_json, occurred_at, created_at, deleted_at
                FROM classroom_usage_events WHERE user_id = :u ORDER BY occurred_at, id""", user);
        array(g, "teachingRoles", """
                SELECT m.term_id, t.name AS term_name, s.name AS class_name, m.role, m.status, m.created_at, m.updated_at, m.revoked_at
                FROM class_role_memberships m JOIN terms t ON t.id = m.term_id LEFT JOIN class_sections s ON s.id = t.class_section_id
                WHERE m.user_id = :u ORDER BY m.created_at, m.id""", user);
        array(g, "ownedClasses", """
                SELECT c.id AS class_id, c.school_id, sc.name AS school_name, c.name, c.code, c.status, c.created_at, c.updated_at, c.deleted_at
                FROM class_sections c LEFT JOIN schools sc ON sc.id = c.school_id
                WHERE c.owner_user_id = :u ORDER BY c.created_at, c.id""", user);
        array(g, "schoolMemberships", """
                SELECT m.school_id, sc.name AS school_name, m.role, m.status, m.created_at, m.updated_at, m.revoked_at
                FROM school_memberships m LEFT JOIN schools sc ON sc.id = m.school_id
                WHERE m.user_id = :u ORDER BY m.created_at, m.id""", user);
        teacherContent(g, user);
        g.writeEndObject();
        g.writeArrayFieldStart("notes");
        g.writeString("Anything stored only in this browser (for example, reading position before you signed in) is not in this file.");
        g.writeString("Sign-in credentials and session data are not exported.");
        g.writeEndArray();
        g.writeEndObject();
    }

    /**
     * Classwork the account created or configures as a teacher: terms and settings of classes it
     * owns, and assignments, assignment quizzes, question overrides, and invite links it created.
     * Invite codes (hash and hint) are never exported. Student-owned data is not part of this section.
     */
    private void teacherContent(JsonGenerator g, MapSqlParameterSource user) throws IOException {
        g.writeObjectFieldStart("teacherContent");
        array(g, "classTerms", """
                SELECT t.id AS term_id, s.id AS class_id, s.name AS class_name, t.name, t.start_date, t.end_date, t.status,
                       t.retention_purge_after, t.created_at, t.updated_at, t.deleted_at
                FROM terms t JOIN class_sections s ON s.id = t.class_section_id
                WHERE s.owner_user_id = :u ORDER BY t.created_at, t.id""", user);
        array(g, "featureSettings", """
                SELECT f.term_id, f.quiz_enabled, f.recap_enabled, f.tts_enabled, f.illustration_enabled, f.character_enabled,
                       f.chat_enabled, f.speed_reading_enabled, f.reading_buddy_enabled, f.default_quiz_question_count,
                       f.default_quiz_option_count, f.default_quiz_pass_min_correct, f.default_quiz_max_retries, f.updated_at
                FROM class_feature_settings f JOIN terms t ON t.id = f.term_id JOIN class_sections s ON s.id = t.class_section_id
                WHERE s.owner_user_id = :u OR f.updated_by_user_id = :u ORDER BY f.term_id""", user);
        array(g, "assignments", """
                SELECT a.id AS assignment_id, a.term_id, a.title, a.book_id, a.due_date, a.available_from_date, a.quiz_required,
                       a.quiz_source, a.quiz_pass_min_correct, a.quiz_max_retries, a.quiz_rules_activated_at,
                       a.character_chat_required, a.sort_order, a.status, a.created_at, a.updated_at, a.deleted_at
                FROM assignments a WHERE """ + MY_ASSIGNMENTS + " ORDER BY a.created_at, a.id", user);
        array(g, "assignmentChapters", """
                SELECT c.assignment_id, c.chapter_id, c.chapter_index, c.sort_order
                FROM assignment_chapters c JOIN assignments a ON a.id = c.assignment_id
                WHERE """ + MY_ASSIGNMENTS + " ORDER BY c.assignment_id, c.sort_order, c.id", user);
        array(g, "assignmentQuizzes", """
                SELECT q.assignment_id, q.payload_json, q.created_at, q.updated_at
                FROM assignment_quizzes q JOIN assignments a ON a.id = q.assignment_id
                WHERE """ + MY_ASSIGNMENTS + " ORDER BY q.created_at, q.id", user);
        array(g, "quizQuestionOverrides", """
                SELECT term_id, book_id, chapter_id, operation, source_question_id, overlay_key, sort_order, question_json,
                       status, base_prompt_version, notes, created_at, updated_at, deleted_at
                FROM quiz_question_overrides WHERE created_by_user_id = :u ORDER BY created_at, id""", user);
        array(g, "inviteLinks", """
                SELECT term_id, label, max_uses, use_count, expires_at, revoked_at, created_at, updated_at
                FROM invite_links WHERE created_by_user_id = :u ORDER BY created_at, id""", user);
        g.writeEndObject();
    }

    /** One conversation object per character chat, messages inline, from a single ordered cursor. */
    private void characterChats(JsonGenerator g, MapSqlParameterSource user) throws IOException {
        g.writeArrayFieldStart("characterChats");
        String[] current = {null};
        jdbc.query("""
                SELECT cc.id AS conversation_id, cc.character_id, ch.name AS character_name, ch.book_id, b.title AS book_title,
                       cc.context_chapter_id, cc.context_chapter_index, cc.context_chapter_title, cc.context_paragraph_index,
                       cc.created_at AS conversation_created_at, cc.updated_at AS conversation_updated_at,
                       m.sequence_number, m.role, m.content, m.created_at
                FROM character_chat_conversations cc
                LEFT JOIN characters ch ON ch.id = cc.character_id LEFT JOIN books b ON b.id = ch.book_id
                LEFT JOIN character_chat_messages m ON m.conversation_id = cc.id AND m.user_id = cc.user_id
                WHERE cc.user_id = :u ORDER BY cc.created_at, cc.id, m.sequence_number""", user, (RowCallbackHandler) rs -> {
            try {
                String conversation = rs.getString("conversation_id");
                if (!conversation.equals(current[0])) {
                    if (current[0] != null) {
                        g.writeEndArray();
                        g.writeEndObject();
                    }
                    current[0] = conversation;
                    g.writeStartObject();
                    for (String column : List.of("character_id", "character_name", "book_id", "book_title", "context_chapter_id",
                            "context_chapter_index", "context_chapter_title", "context_paragraph_index")) {
                        field(g, column, rs.getObject(column));
                    }
                    field(g, "created_at", rs.getObject("conversation_created_at"));
                    field(g, "updated_at", rs.getObject("conversation_updated_at"));
                    g.writeArrayFieldStart("messages");
                }
                if (rs.getObject("sequence_number") != null) {
                    g.writeStartObject();
                    for (String column : List.of("sequence_number", "role", "content", "created_at")) {
                        field(g, column, rs.getObject(column));
                    }
                    g.writeEndObject();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (current[0] != null) {
            g.writeEndArray();
            g.writeEndObject();
        }
        g.writeEndArray();
    }

    private void array(JsonGenerator g, String name, String sql, MapSqlParameterSource params) throws IOException {
        g.writeArrayFieldStart(name);
        jdbc.query(sql, params, (RowCallbackHandler) rs -> row(g, rs));
        g.writeEndArray();
    }

    /** First row as an object, or null. */
    private void object(JsonGenerator g, String sql, MapSqlParameterSource params) throws IOException {
        boolean[] wrote = {false};
        jdbc.query(sql, params, (RowCallbackHandler) rs -> {
            if (!wrote[0]) {
                row(g, rs);
                wrote[0] = true;
            }
        });
        if (!wrote[0]) {
            g.writeNull();
        }
    }

    private void row(JsonGenerator g, ResultSet rs) throws SQLException {
        try {
            ResultSetMetaData meta = rs.getMetaData();
            g.writeStartObject();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                field(g, meta.getColumnLabel(i).toLowerCase(), rs.getObject(i));
            }
            g.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void field(JsonGenerator g, String name, Object value) throws IOException {
        Object portable = portable(value);
        // Stored JSON documents (reader state, quiz payloads, question overrides) are written as JSON, not strings.
        if (name.endsWith("_json") && portable instanceof String text && !text.isBlank()) {
            g.writeFieldName(name);
            try {
                g.writeTree(json.readTree(text));
            } catch (IOException notJson) {
                g.writeString(text);
            }
            return;
        }
        g.writeObjectField(name, portable);
    }

    /** JDBC values vary by driver (H2 returns Clob for TEXT); normalize to JSON-friendly types. */
    private static Object portable(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.time.temporal.TemporalAccessor temporal) {
            return temporal.toString();
        }
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[4096];
                for (int n; (n = reader.read(buffer)) > 0; ) {
                    text.append(buffer, 0, n);
                }
                return text.toString();
            } catch (Exception e) {
                throw new IllegalStateException("Could not read text column", e);
            }
        }
        return value;
    }
}
