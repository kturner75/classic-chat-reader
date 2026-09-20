package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real Flyway schema: which terms purge, what is deleted, what is deliberately kept. */
@DataJpaTest
class TermRetentionPurgeServiceTest {

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbc;

    /** 2028-01-20: 404 days after a 2026-12-12 term end. */
    private static final Clock NOW = Clock.fixed(Instant.parse("2028-01-20T12:00:00Z"), ZoneOffset.UTC);

    private TermRetentionPurgeService service(Clock clock) {
        return new TermRetentionPurgeService(dataSource, transactionManager, new ClassroomProperties(), clock);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private void term(String id, String endDate, String purgeAfter, String status, boolean deleted) {
        jdbc.update("INSERT INTO terms (id, class_section_id, name, status, start_date, end_date, retention_purge_after, created_at, updated_at, deleted_at) "
                        + "VALUES (?, 'p-class', ?, ?, DATE '2026-08-24', " + (endDate == null ? "NULL" : "DATE '" + endDate + "'") + ", "
                        + (purgeAfter == null ? "NULL" : "TIMESTAMP '" + purgeAfter + "'") + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                        + (deleted ? "CURRENT_TIMESTAMP" : "NULL") + ")",
                id, id, status);
    }

    private void student(String userId, String termId) {
        jdbc.update("INSERT INTO users (id, email, created_at, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", userId, userId + "@example.test");
        jdbc.update("INSERT INTO enrollments (id, term_id, user_id, role, status, joined_date, created_at, updated_at) VALUES (?, ?, ?, 'STUDENT', 'ACTIVE', DATE '2026-08-24', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "enr-" + userId, termId, userId);
        jdbc.update("INSERT INTO classroom_usage_events (id, user_id, term_id, event_type, occurred_at, created_at) VALUES (?, ?, ?, 'READING_HEARTBEAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "cue-" + userId, userId, termId);
        jdbc.update("INSERT INTO assignment_progress (id, term_id, assignment_id, user_id, first_opened_at, created_at, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "ap-" + userId, termId, "asg-" + termId, userId);
        jdbc.update("INSERT INTO reading_buddy_messages (id, owner_key, book_id, persona_id, role, content, kind, chapter_index, paragraph_index, content_hash, created_at, chronology_sequence) "
                + "VALUES (?, ?, 'p-book', 'sage', 'user', 'mine', 'chat', 0, 0, 'h', CURRENT_TIMESTAMP, 1)", "rbm-" + userId, "user:" + userId);
        jdbc.update("INSERT INTO quiz_attempts (id, chapter_id, user_id, assignment_id, correct_answers, total_questions, score_percent, perfect, difficulty_level, created_at) "
                + "VALUES (?, 'p-ch', ?, ?, 4, 5, 80, FALSE, 1, CURRENT_TIMESTAMP)", "qa-" + userId, userId, "asg-" + termId);
    }

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO users (id, email, created_at, updated_at) VALUES ('p-teacher', 'p-teacher@example.test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO books (id, source, source_id, title, author) VALUES ('p-book', 'gutenberg', 'p-1', 'Book', 'Author')");
        jdbc.update("INSERT INTO chapters (id, book_id, chapter_index, title) VALUES ('p-ch', 'p-book', 0, 'Chapter I')");
        jdbc.update("INSERT INTO class_sections (id, owner_user_id, name, status, created_at, updated_at) VALUES ('p-class', 'p-teacher', 'English 101', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        term("ended-long-ago", "2026-12-12", null, "ACTIVE", false);   // 404 days ago: eligible
        term("ended-recently", "2027-01-01", null, "ENDED", false);    // 384 days ago: not yet
        term("no-end-date", null, null, "ACTIVE", false);              // never
        term("explicit-past", "2027-12-01", "2028-01-01 00:00:00", "ENDED", false); // explicit date passed
        term("explicit-future", "2020-01-01", "2029-01-01 00:00:00", "ENDED", false); // explicit date wins over old end date
        term("already-purged", "2025-01-01", null, "PURGED", false);
        term("deleted", "2025-01-01", null, "ENDED", true);   // hidden, but its records still purge
        for (String t : List.of("ended-long-ago", "ended-recently", "explicit-past")) {
            jdbc.update("INSERT INTO assignments (id, term_id, title, book_id, status, sort_order, created_by_user_id, created_at, updated_at) "
                    + "VALUES (?, ?, 'Read', 'p-book', 'PUBLISHED', 0, 'p-teacher', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "asg-" + t, t);
        }
        jdbc.update("INSERT INTO class_role_memberships (id, term_id, user_id, role, status, created_at, updated_at) VALUES ('crm-p', 'ended-long-ago', 'p-teacher', 'TEACHER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        student("s-old", "ended-long-ago");
        student("s-recent", "ended-recently");
        jdbc.update("INSERT INTO education_record_access_logs (id, actor_user_id, subject_user_id, term_id, access_type, occurred_at, retain_until) VALUES "
                + "('log-expired', 'p-teacher', 's-old', 'ended-long-ago', 'VIEW_ROSTER', TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2027-01-01 00:00:00'),"
                + "('log-kept', 'p-teacher', 's-old', 'ended-long-ago', 'VIEW_ROSTER', TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2033-09-01 00:00:00')");
        jdbc.update("INSERT INTO chat_export_jobs (id, requester_user_id, subject_user_id, term_id, format, status, chat_sources, created_at) VALUES "
                + "('job-old', 'p-teacher', 's-old', 'ended-long-ago', 'JSON', 'READY', 'READING_BUDDY', TIMESTAMP '2020-01-01 00:00:00'),"
                + "('job-kept', 'p-teacher', 's-old', 'ended-long-ago', 'JSON', 'READY', 'READING_BUDDY', TIMESTAMP '2026-10-01 00:00:00')");
    }

    @Test
    void eligibilityFollowsExplicitPurgeDatesThenEndDatePlusRetention() {
        assertEquals(List.of("deleted", "ended-long-ago", "explicit-past"), service(NOW).eligibleTermIds(),
                "a soft-deleted term past retention must not keep its student records forever");
    }

    @Test
    void purgeDeletesTheTermsStudentRecordsAndKeepsEverythingElse() {
        TermRetentionPurgeService.RunResult result = service(NOW).runOnce();

        assertEquals(List.of("deleted", "ended-long-ago", "explicit-past"), result.purgedTerms().stream().map(TermRetentionPurgeService.TermPurge::termId).toList());
        assertTrue(result.failedTermIds().isEmpty());
        for (String table : List.of("enrollments", "classroom_usage_events", "assignment_progress")) {
            assertEquals(0, count("SELECT COUNT(*) FROM " + table + " WHERE term_id = 'ended-long-ago'"), table);
            assertEquals(1, count("SELECT COUNT(*) FROM " + table + " WHERE term_id = 'ended-recently'"), table + " of a term still in retention");
        }
        assertEquals("PURGED", jdbc.queryForObject("SELECT status FROM terms WHERE id = 'ended-long-ago'", String.class));
        assertNotNull(jdbc.queryForObject("SELECT retention_purge_after FROM terms WHERE id = 'ended-long-ago'", Object.class));
        assertEquals("ENDED", jdbc.queryForObject("SELECT status FROM terms WHERE id = 'ended-recently'", String.class));

        // Deliberately kept: student-owned data, teacher content and roles, the users themselves.
        assertEquals(1, count("SELECT COUNT(*) FROM reading_buddy_messages WHERE owner_key = 'user:s-old'"));
        assertEquals(1, count("SELECT COUNT(*) FROM quiz_attempts WHERE user_id = 's-old'"));
        assertEquals(1, count("SELECT COUNT(*) FROM assignments WHERE term_id = 'ended-long-ago'"));
        assertEquals(1, count("SELECT COUNT(*) FROM class_role_memberships WHERE term_id = 'ended-long-ago'"));
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id = 's-old'"));

        // Compliance rows leave only after their own retention.
        assertEquals(1, result.accessLogsDeleted());
        assertEquals(List.of("log-kept"), jdbc.queryForList("SELECT id FROM education_record_access_logs", String.class));
        assertEquals(1, result.exportJobsDeleted());
        assertEquals(List.of("job-kept"), jdbc.queryForList("SELECT id FROM chat_export_jobs", String.class));
    }

    @Test
    void aSecondRunFindsNothingToDo() {
        service(NOW).runOnce();
        TermRetentionPurgeService.RunResult again = service(NOW).runOnce();
        assertTrue(again.purgedTerms().isEmpty());
        assertEquals(0, again.accessLogsDeleted());
        assertEquals(0, again.exportJobsDeleted());
    }

    @Test
    void rowsWrittenIntoAnAlreadyPurgedTermAreSweptOnTheNextRun() {
        service(NOW).runOnce();
        // An in-flight request that passed its term check just before the term was marked PURGED.
        jdbc.update("INSERT INTO enrollments (id, term_id, user_id, role, status, joined_date, created_at, updated_at) "
                + "VALUES ('late-enr', 'ended-long-ago', 's-recent', 'STUDENT', 'ACTIVE', DATE '2026-08-24', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO classroom_usage_events (id, user_id, term_id, event_type, occurred_at, created_at) "
                + "VALUES ('late-cue', 's-old', 'ended-long-ago', 'READING_HEARTBEAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

        service(NOW).runOnce();

        assertEquals(0, count("SELECT COUNT(*) FROM enrollments WHERE term_id = 'ended-long-ago'"));
        assertEquals(0, count("SELECT COUNT(*) FROM classroom_usage_events WHERE term_id = 'ended-long-ago'"));
        assertEquals(1, count("SELECT COUNT(*) FROM enrollments WHERE term_id = 'ended-recently'"), "terms still in retention are untouched");
    }

    @Test
    void exportRecordsLeaveOnTheirOwnExpiry() {
        jdbc.update("UPDATE chat_export_jobs SET expires_at = TIMESTAMP '2027-01-01 00:00:00', created_at = CURRENT_TIMESTAMP WHERE id = 'job-old'");
        jdbc.update("UPDATE chat_export_jobs SET expires_at = TIMESTAMP '2030-01-01 00:00:00', created_at = TIMESTAMP '2020-01-01 00:00:00' WHERE id = 'job-kept'");

        service(NOW).runOnce();

        assertEquals(List.of("job-kept"), jdbc.queryForList("SELECT id FROM chat_export_jobs", String.class),
                "expires_at decides, even when the row is older than the age fallback");
    }

    @Test
    void theScheduledRunDoesNothingWhileThePurgeIsDisabled() {
        ClassroomProperties disabled = new ClassroomProperties();
        ClassroomProperties.Ferpa ferpa = new ClassroomProperties.Ferpa();
        ferpa.setPurgeEnabled(false);
        disabled.setFerpa(ferpa);
        new TermRetentionPurgeService(dataSource, transactionManager, disabled, NOW).scheduledRun();

        assertEquals(1, count("SELECT COUNT(*) FROM enrollments WHERE term_id = 'ended-long-ago'"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM terms WHERE id = 'ended-long-ago'", String.class));
    }

    @Test
    void theCutoffFollowsTheClassroomCalendarZoneNotTheServerDate() {
        // The 03:30 UTC run on 2028-01-17 is still the evening of 2028-01-16 in Los Angeles, the last
        // local day of the 400-day window for a term that ended 2026-12-12.
        Clock justAfterUtcMidnight = Clock.fixed(Instant.parse("2028-01-17T03:30:00Z"), ZoneOffset.UTC);
        ClassroomProperties western = new ClassroomProperties();
        western.setCalendarZone("America/Los_Angeles");
        var service = new TermRetentionPurgeService(dataSource, transactionManager, western, justAfterUtcMidnight);
        assertFalse(service.eligibleTermIds().contains("ended-long-ago"),
                "the school's calendar day decides, so nothing purges before the local retention day ends");

        ClassroomProperties utc = new ClassroomProperties();
        utc.setCalendarZone("UTC");
        assertTrue(new TermRetentionPurgeService(dataSource, transactionManager, utc, justAfterUtcMidnight)
                .eligibleTermIds().contains("ended-long-ago"));
    }

    @Test
    void nothingPurgesBeforeTheRetentionPeriodEnds() {
        // 2027-06-01: 'ended-long-ago' (ends 2026-12-12) and 'explicit-past' (2028-01-01) are both
        // still inside their retention window. Only the long-ended soft-deleted term qualifies.
        Clock beforeAnyExpiry = Clock.fixed(Instant.parse("2027-06-01T00:00:00Z"), ZoneOffset.UTC);
        assertEquals(List.of("deleted"), service(beforeAnyExpiry).eligibleTermIds());
    }
}
