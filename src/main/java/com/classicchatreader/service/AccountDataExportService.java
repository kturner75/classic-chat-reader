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
 * <p>Streams: rows are written to the response as they are read, in one read-only transaction with
 * a cursor fetch size, so a long usage history never has to fit in memory.
 *
 * <p>Explicit column lists only: credentials, sessions, sign-in identities, capability grants,
 * internal reader ids, and other people's data are never included. Soft-deleted rows the account
 * still owns are included (with their {@code deleted_at}); they are held data until purged.
 */
@Service
public class AccountDataExportService {

    private static final int FETCH_SIZE = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final ObjectMapper json = new ObjectMapper();

    public AccountDataExportService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(FETCH_SIZE);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
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
        object(g, "SELECT id, email, created_at FROM users WHERE id = :u", user);
        g.writeFieldName("readerState");
        object(g, "SELECT state_json, updated_at FROM user_reader_states WHERE user_id = :u", user);
        array(g, "annotations", """
                SELECT a.book_id, b.title AS book_title, a.chapter_id, a.paragraph_index, a.highlighted,
                       a.note_text, a.bookmarked, a.created_at, a.updated_at
                FROM paragraph_annotations a LEFT JOIN books b ON b.id = a.book_id
                WHERE a.user_id = :u ORDER BY a.updated_at, a.id""", user);
        array(g, "quizAttempts", """
                SELECT c.book_id, q.chapter_id, c.title AS chapter_title, q.assignment_id, q.correct_answers,
                       q.total_questions, q.score_percent, q.perfect, q.difficulty_level, q.created_at
                FROM quiz_attempts q LEFT JOIN chapters c ON c.id = q.chapter_id
                WHERE q.user_id = :u ORDER BY q.created_at, q.id""", user);
        array(g, "quizTrophies", """
                SELECT book_id, code, title, description, unlocked_at
                FROM quiz_trophies WHERE user_id = :u ORDER BY unlocked_at, id""", user);
        characterChats(g, user);
        g.writeObjectFieldStart("readingBuddy");
        array(g, "messages", """
                SELECT book_id, persona_id, role, kind, content, chapter_index, paragraph_index, created_at
                FROM reading_buddy_messages WHERE owner_key = :k ORDER BY created_at, chronology_sequence, id""", user);
        array(g, "memories", """
                SELECT book_id, persona_id, summary_text, updated_at
                FROM reading_buddy_memories WHERE owner_key = :k ORDER BY updated_at, id""", user);
        array(g, "preferences", """
                SELECT book_id, enabled, frequency, default_persona_id, persona_id, suppress_until, updated_at
                FROM reading_buddy_preferences WHERE owner_key = :k ORDER BY updated_at, id""", user);
        g.writeEndObject();
        g.writeObjectFieldStart("classroom");
        array(g, "enrollments", """
                SELECT e.term_id, t.name AS term_name, s.name AS class_name, e.role, e.status,
                       e.joined_date, e.left_date, e.display_name_override, e.deleted_at
                FROM enrollments e JOIN terms t ON t.id = e.term_id LEFT JOIN class_sections s ON s.id = t.class_section_id
                WHERE e.user_id = :u ORDER BY e.joined_date, e.id""", user);
        array(g, "assignmentProgress", """
                SELECT term_id, assignment_id, first_opened_at
                FROM assignment_progress WHERE user_id = :u ORDER BY first_opened_at, id""", user);
        array(g, "usageEvents", """
                SELECT term_id, event_type, book_id, chapter_id, assignment_id, duration_ms, progress_percent, feature, occurred_at, deleted_at
                FROM classroom_usage_events WHERE user_id = :u ORDER BY occurred_at, id""", user);
        array(g, "teachingRoles", """
                SELECT m.term_id, t.name AS term_name, s.name AS class_name, m.role, m.status, m.created_at, m.revoked_at
                FROM class_role_memberships m JOIN terms t ON t.id = m.term_id LEFT JOIN class_sections s ON s.id = t.class_section_id
                WHERE m.user_id = :u ORDER BY m.created_at, m.id""", user);
        array(g, "ownedClasses", """
                SELECT id AS class_id, name, code, status, created_at, deleted_at
                FROM class_sections WHERE owner_user_id = :u ORDER BY created_at, id""", user);
        array(g, "schoolMemberships", """
                SELECT school_id, role, status, created_at, revoked_at
                FROM school_memberships WHERE user_id = :u ORDER BY created_at, id""", user);
        g.writeEndObject();
        g.writeArrayFieldStart("notes");
        g.writeString("Anything stored only in this browser (for example, reading position before you signed in) is not in this file.");
        g.writeString("Sign-in credentials and session data are not exported.");
        g.writeEndArray();
        g.writeEndObject();
    }

    /** One conversation object per character chat, messages inline, from a single ordered cursor. */
    private void characterChats(JsonGenerator g, MapSqlParameterSource user) throws IOException {
        g.writeArrayFieldStart("characterChats");
        String[] current = {null};
        jdbc.query("""
                SELECT cc.id AS conversation_id, cc.character_id, ch.name AS character_name, ch.book_id, b.title AS book_title,
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
                    for (String column : List.of("character_id", "character_name", "book_id", "book_title")) {
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
        if ("state_json".equals(name) && portable instanceof String text && !text.isBlank()) {
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
