package com.classicchatreader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.Reader;
import java.sql.Clob;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Download my data" for a signed-in account (BL-043.6). The export-first notice before account
 * deletion points here. Covers what the account created or accrued: reader state, annotations,
 * quizzes, character chats, Reading Buddy, and the account's own classroom records.
 *
 * <p>Explicit column lists only: credentials, sessions, sign-in identities, capability grants,
 * and other people's data are never included.
 */
@Service
public class AccountDataExportService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public AccountDataExportService(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Transactional(readOnly = true)
    public byte[] export(String userId) {
        MapSqlParameterSource user = new MapSqlParameterSource("u", userId).addValue("k", "user:" + userId);
        Map<String, Object> document = new LinkedHashMap<>();
        List<Map<String, Object>> account = rows("SELECT id, email, created_at FROM users WHERE id = :u", user);
        if (account.isEmpty()) {
            throw new IllegalArgumentException("Account not found");
        }
        document.put("exportedAt", LocalDateTime.now(ZoneOffset.UTC).toString());
        document.put("account", account.getFirst());
        document.put("readerState", rows("SELECT state_json, updated_at FROM user_reader_states WHERE user_id = :u", user).stream()
                .findFirst().map(row -> {
                    Map<String, Object> state = new LinkedHashMap<>(row);
                    state.put("state_json", parseJson((String) row.get("state_json")));
                    return state;
                }).orElse(null));
        document.put("annotations", rows("""
                SELECT a.book_id, b.title AS book_title, a.chapter_id, a.paragraph_index, a.highlighted,
                       a.note_text, a.bookmarked, a.created_at, a.updated_at
                FROM paragraph_annotations a LEFT JOIN books b ON b.id = a.book_id
                WHERE a.user_id = :u ORDER BY a.updated_at, a.id""", user));
        document.put("quizAttempts", rows("""
                SELECT c.book_id, q.chapter_id, c.title AS chapter_title, q.assignment_id, q.correct_answers,
                       q.total_questions, q.score_percent, q.perfect, q.difficulty_level, q.created_at
                FROM quiz_attempts q LEFT JOIN chapters c ON c.id = q.chapter_id
                WHERE q.user_id = :u ORDER BY q.created_at, q.id""", user));
        document.put("quizTrophies", rows("""
                SELECT book_id, code, title, description, unlocked_at
                FROM quiz_trophies WHERE user_id = :u ORDER BY unlocked_at, id""", user));
        document.put("characterChats", characterChats(user));
        Map<String, Object> buddy = new LinkedHashMap<>();
        buddy.put("messages", rows("""
                SELECT book_id, persona_id, role, kind, content, chapter_index, paragraph_index, created_at
                FROM reading_buddy_messages WHERE owner_key = :k ORDER BY created_at, chronology_sequence, id""", user));
        buddy.put("memories", rows("""
                SELECT book_id, persona_id, summary_text, updated_at
                FROM reading_buddy_memories WHERE owner_key = :k ORDER BY updated_at, id""", user));
        buddy.put("preferences", rows("""
                SELECT book_id, enabled, frequency, default_persona_id, persona_id, suppress_until, updated_at
                FROM reading_buddy_preferences WHERE owner_key = :k ORDER BY updated_at, id""", user));
        document.put("readingBuddy", buddy);
        Map<String, Object> classroom = new LinkedHashMap<>();
        classroom.put("enrollments", rows("""
                SELECT e.term_id, t.name AS term_name, s.name AS class_name, e.role, e.status,
                       e.joined_date, e.left_date, e.display_name_override
                FROM enrollments e JOIN terms t ON t.id = e.term_id LEFT JOIN class_sections s ON s.id = t.class_section_id
                WHERE e.user_id = :u AND e.deleted_at IS NULL ORDER BY e.joined_date, e.id""", user));
        classroom.put("assignmentProgress", rows("""
                SELECT term_id, assignment_id, first_opened_at
                FROM assignment_progress WHERE user_id = :u ORDER BY first_opened_at, id""", user));
        classroom.put("usageEvents", rows("""
                SELECT term_id, event_type, book_id, chapter_id, assignment_id, duration_ms, progress_percent, feature, occurred_at
                FROM classroom_usage_events WHERE user_id = :u AND deleted_at IS NULL ORDER BY occurred_at, id""", user));
        document.put("classroom", classroom);
        document.put("notes", List.of(
                "Anything stored only in this browser (for example, reading position before you signed in) is not in this file.",
                "Sign-in credentials and session data are not exported."));
        try {
            return json.writeValueAsBytes(document);
        } catch (Exception e) {
            throw new IllegalStateException("Account export could not be serialized", e);
        }
    }

    private List<Map<String, Object>> characterChats(MapSqlParameterSource user) {
        List<Map<String, Object>> conversations = rows("""
                SELECT cc.id, cc.character_id, ch.name AS character_name, ch.book_id, b.title AS book_title,
                       cc.created_at, cc.updated_at
                FROM character_chat_conversations cc
                LEFT JOIN characters ch ON ch.id = cc.character_id LEFT JOIN books b ON b.id = ch.book_id
                WHERE cc.user_id = :u ORDER BY cc.created_at, cc.id""", user);
        Map<Object, List<Map<String, Object>>> byConversation = new LinkedHashMap<>();
        for (Map<String, Object> message : rows("""
                SELECT conversation_id, sequence_number, role, content, created_at
                FROM character_chat_messages WHERE user_id = :u ORDER BY conversation_id, sequence_number""", user)) {
            byConversation.computeIfAbsent(message.remove("conversation_id"), key -> new ArrayList<>()).add(message);
        }
        for (Map<String, Object> conversation : conversations) {
            conversation.put("messages", byConversation.getOrDefault(conversation.remove("id"), List.of()));
        }
        return conversations;
    }

    private List<Map<String, Object>> rows(String sql, MapSqlParameterSource params) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, params)) {
            Map<String, Object> clean = new LinkedHashMap<>();
            row.forEach((column, value) -> clean.put(column.toLowerCase(), portable(value)));
            out.add(clean);
        }
        return out;
    }

    /** JDBC values vary by driver (H2 returns Clob for TEXT); normalize to JSON-friendly types. */
    private static Object portable(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
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

    private JsonNode parseJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return json.readTree(value);
        } catch (Exception e) {
            return json.getNodeFactory().textNode(value);
        }
    }
}
