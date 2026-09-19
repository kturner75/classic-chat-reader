package com.classicchatreader.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-initiated account deletion (BL-043.6, BL-021 "hard-delete within 24 hours").
 *
 * <p>Policy (Kevin, 2026-09-18): enrolled students may delete their account; the UI shows an
 * export-first notice listing their classes instead of blocking. Deletion is immediate and
 * complete in one transaction: sessions, reader data, chats, quiz history, and the student's
 * classroom records are removed. FERPA access logs and chat export records are compliance
 * records, so they are kept with the user id replaced by a stable pseudonym.
 *
 * <p>Accounts with a teacher-side footprint (own a class, hold a teacher role, or authored class
 * content) are not deleted here: other people's classes depend on those rows.
 */
@Service
public class AccountDeletionService {

    public record ClassMembership(String className, String termName, String status) {}

    public record DeletionPreview(String email, boolean passwordRequired, List<ClassMembership> classes, String blockedReason) {}

    public record DeletionResult(String pseudonym, Map<String, Integer> deletedRows) {}

    /** Tables deleted by user id, in dependency order (children before parents). */
    private static final List<String[]> USER_ROWS = List.of(
            new String[]{"character_chat_messages", "user_id"},
            new String[]{"character_chat_conversations", "user_id"},
            new String[]{"paragraph_annotations", "user_id"},
            new String[]{"quiz_attempts", "user_id"},
            new String[]{"quiz_trophies", "user_id"},
            new String[]{"classroom_usage_events", "user_id"},
            new String[]{"assignment_progress", "user_id"},
            new String[]{"enrollments", "user_id"},
            new String[]{"school_memberships", "user_id"},
            new String[]{"account_capabilities", "user_id"},
            new String[]{"user_reader_claims", "user_id"},
            new String[]{"user_reader_states", "user_id"},
            new String[]{"user_sessions", "user_id"},
            new String[]{"pending_external_identity_links", "user_id"},
            new String[]{"user_auth_identities", "user_id"},
            new String[]{"user_local_credentials", "user_id"});

    private static final List<String> OWNER_KEY_ROWS = List.of(
            "reading_buddy_messages", "reading_buddy_memories", "reading_buddy_preferences");

    private final NamedParameterJdbcTemplate jdbc;

    public AccountDeletionService(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** Stable, non-reversible stand-in for a deleted user id in retained compliance rows. */
    public static String pseudonym(String userId) {
        return "deleted:" + RequestPrivacy.hash("account:" + userId);
    }

    @Transactional(readOnly = true)
    public DeletionPreview preview(String userId, boolean passwordRequired) {
        MapSqlParameterSource u = new MapSqlParameterSource("u", userId);
        String email = jdbc.queryForList("SELECT email FROM users WHERE id = :u", u, String.class).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        List<ClassMembership> classes = jdbc.query("""
                SELECT s.name AS class_name, t.name AS term_name, e.status
                FROM enrollments e JOIN terms t ON t.id = e.term_id LEFT JOIN class_sections s ON s.id = t.class_section_id
                WHERE e.user_id = :u AND e.deleted_at IS NULL ORDER BY e.joined_date""", u,
                (row, i) -> new ClassMembership(row.getString("class_name"), row.getString("term_name"), row.getString("status")));
        return new DeletionPreview(email, passwordRequired, classes, blockedReason(u));
    }

    private String blockedReason(MapSqlParameterSource u) {
        int footprint = count("SELECT COUNT(*) FROM class_sections WHERE owner_user_id = :u", u)
                + count("SELECT COUNT(*) FROM class_role_memberships WHERE user_id = :u", u)
                + count("SELECT COUNT(*) FROM assignments WHERE created_by_user_id = :u", u)
                + count("SELECT COUNT(*) FROM assignment_quizzes WHERE created_by_user_id = :u", u)
                + count("SELECT COUNT(*) FROM invite_links WHERE created_by_user_id = :u", u)
                + count("SELECT COUNT(*) FROM quiz_question_overrides WHERE created_by_user_id = :u", u)
                + count("SELECT COUNT(*) FROM class_feature_settings WHERE updated_by_user_id = :u", u)
                + count("SELECT COUNT(*) FROM account_capabilities WHERE granted_by_user_id = :u", u);
        return footprint > 0
                ? "This account teaches or manages classes. Contact support to delete a teacher account so your classes can be handed over first."
                : null;
    }

    /** Caller must have re-authenticated the account owner. Runs in one transaction: all or nothing. */
    @Transactional
    public DeletionResult delete(String userId) {
        MapSqlParameterSource u = new MapSqlParameterSource("u", userId)
                .addValue("k", "user:" + userId)
                .addValue("p", pseudonym(userId));
        if (count("SELECT COUNT(*) FROM users WHERE id = :u", u) != 1) {
            throw new IllegalArgumentException("Account not found");
        }
        String blocked = blockedReason(u);
        if (blocked != null) {
            throw new IllegalStateException(blocked);
        }
        Map<String, Integer> deleted = new LinkedHashMap<>();
        // Keep compliance records, but never with a live user id.
        deleted.put("education_record_access_logs (pseudonymized)",
                jdbc.update("UPDATE education_record_access_logs SET subject_user_id = :p WHERE subject_user_id = :u", u)
                        + jdbc.update("UPDATE education_record_access_logs SET actor_user_id = :p WHERE actor_user_id = :u", u));
        deleted.put("chat_export_jobs (pseudonymized)",
                jdbc.update("UPDATE chat_export_jobs SET subject_user_id = :p WHERE subject_user_id = :u", u)
                        + jdbc.update("UPDATE chat_export_jobs SET requester_user_id = :p WHERE requester_user_id = :u", u));
        for (String table : OWNER_KEY_ROWS) {
            deleted.put(table, jdbc.update("DELETE FROM " + table + " WHERE owner_key = :k", u));
        }
        for (String[] table : USER_ROWS) {
            deleted.put(table[0], jdbc.update("DELETE FROM " + table[0] + " WHERE " + table[1] + " = :u", u));
        }
        deleted.put("users", jdbc.update("DELETE FROM users WHERE id = :u", u));
        return new DeletionResult(pseudonym(userId), deleted);
    }

    private int count(String sql, MapSqlParameterSource params) {
        Integer n = jdbc.queryForObject(sql, params, Integer.class);
        return n == null ? 0 : n;
    }
}
