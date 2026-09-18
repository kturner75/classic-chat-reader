package com.classicchatreader.service;

import com.classicchatreader.support.AccountDataFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real Flyway schema (including V33): what an account deletion removes, keeps, and refuses. */
@DataJpaTest
@Import(AccountDeletionService.class)
class AccountDeletionServiceTest {

    @Autowired private AccountDeletionService service;
    @Autowired private DataSource dataSource;
    private JdbcTemplate jdbc;

    /** Every table that can hold a row belonging to an account, with the column that says whose. */
    private static final List<String[]> OWNED = List.of(
            new String[]{"users", "id"},
            new String[]{"user_local_credentials", "user_id"},
            new String[]{"user_sessions", "user_id"},
            new String[]{"user_reader_states", "user_id"},
            new String[]{"paragraph_annotations", "user_id"},
            new String[]{"quiz_attempts", "user_id"},
            new String[]{"quiz_trophies", "user_id"},
            new String[]{"character_chat_conversations", "user_id"},
            new String[]{"character_chat_messages", "user_id"},
            new String[]{"enrollments", "user_id"},
            new String[]{"classroom_usage_events", "user_id"});

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int owned(String userId) {
        int total = 0;
        for (String[] table : OWNED) {
            total += count("SELECT COUNT(*) FROM " + table[0] + " WHERE " + table[1] + " = ?", userId);
        }
        for (String table : List.of("reading_buddy_messages", "reading_buddy_memories", "reading_buddy_preferences")) {
            total += count("SELECT COUNT(*) FROM " + table + " WHERE owner_key = ?", "user:" + userId);
        }
        return total;
    }

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        AccountDataFixture.seedShared(jdbc, "fx-teacher");
        jdbc.update("INSERT INTO class_role_memberships (id, term_id, user_id, role, status, created_at, updated_at) VALUES ('crm-1', 'fx-term', 'fx-teacher', 'TEACHER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        AccountDataFixture.seedStudent(jdbc, "fx-alex", "alex");
        AccountDataFixture.seedStudent(jdbc, "fx-sam", "sam");
        jdbc.update("INSERT INTO education_record_access_logs (id, actor_user_id, subject_user_id, term_id, access_type, occurred_at, retain_until) "
                + "VALUES ('eral-1', 'fx-teacher', 'fx-alex', 'fx-term', 'VIEW_STUDENT_OVERVIEW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO chat_export_jobs (id, requester_user_id, subject_user_id, term_id, format, status, chat_sources, created_at) "
                + "VALUES ('cej-1', 'fx-teacher', 'fx-alex', 'fx-term', 'JSON', 'READY', 'READING_BUDDY', CURRENT_TIMESTAMP)");
    }

    @Test
    void previewListsTheClassesAStudentWouldLeave() {
        AccountDeletionService.DeletionPreview preview = service.preview("fx-alex", true);
        assertEquals("fx-alex@example.test", preview.email());
        assertTrue(preview.passwordRequired());
        assertEquals(List.of(new AccountDeletionService.ClassMembership("English 101", "Fall", "ACTIVE")), preview.classes());
        assertNull(preview.blockedReason());
    }

    @Test
    void deletingAStudentRemovesAllTheirDataAndKeepsComplianceRowsUnderAPseudonym() {
        assertEquals(14, owned("fx-alex"), "fixture seeds one row in each of the 14 owned tables");
        int samBefore = owned("fx-sam");

        AccountDeletionService.DeletionResult result = service.delete("fx-alex");

        assertEquals(0, owned("fx-alex"));
        assertEquals(samBefore, owned("fx-sam"), "another student's data is untouched");
        assertEquals(1, count("SELECT COUNT(*) FROM class_sections WHERE id = 'fx-class'"));
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id = 'fx-teacher'"));

        String pseudonym = AccountDeletionService.pseudonym("fx-alex");
        assertEquals(pseudonym, result.pseudonym());
        assertTrue(pseudonym.startsWith("deleted:") && !pseudonym.contains("fx-alex"));
        assertEquals(pseudonym, jdbc.queryForObject("SELECT subject_user_id FROM education_record_access_logs WHERE id = 'eral-1'", String.class));
        assertEquals("fx-teacher", jdbc.queryForObject("SELECT actor_user_id FROM education_record_access_logs WHERE id = 'eral-1'", String.class));
        assertEquals(pseudonym, jdbc.queryForObject("SELECT subject_user_id FROM chat_export_jobs WHERE id = 'cej-1'", String.class));
    }

    @Test
    void teacherAccountsAreBlockedAndNothingIsDeleted() {
        int before = owned("fx-teacher");
        AccountDeletionService.DeletionPreview preview = service.preview("fx-teacher", false);
        assertNotNull(preview.blockedReason());
        IllegalStateException blocked = assertThrows(IllegalStateException.class, () -> service.delete("fx-teacher"));
        assertTrue(blocked.getMessage().contains("teaches or manages classes"));
        assertEquals(before, owned("fx-teacher"));
    }

    @Test
    void unknownAccountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.delete("nobody"));
        assertThrows(IllegalArgumentException.class, () -> service.preview("nobody", false));
    }
}
