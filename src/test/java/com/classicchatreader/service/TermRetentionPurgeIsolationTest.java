package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real, committed transactions: one term failing to purge neither blocks nor half-purges the others. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TermRetentionPurgeIsolationTest {

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO users (id, email, created_at, updated_at) VALUES ('i-teacher', 'i-teacher@example.test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO class_sections (id, owner_user_id, name, status, created_at, updated_at) VALUES ('i-class', 'i-teacher', 'C', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        for (String t : List.of("i-good", "i-stuck")) {
            jdbc.update("INSERT INTO terms (id, class_section_id, name, status, end_date, created_at, updated_at) VALUES (?, 'i-class', ?, 'ENDED', DATE '2020-01-01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", t, t);
            jdbc.update("INSERT INTO users (id, email, created_at, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "u-" + t, t + "@example.test");
            jdbc.update("INSERT INTO classroom_usage_events (id, user_id, term_id, event_type, occurred_at, created_at) VALUES (?, ?, ?, 'READING_HEARTBEAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "cue-" + t, "u-" + t, t);
            jdbc.update("INSERT INTO enrollments (id, term_id, user_id, role, status, joined_date, created_at, updated_at) VALUES (?, ?, ?, 'STUDENT', 'COMPLETED', DATE '2019-09-01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "enr-" + t, t, "u-" + t);
        }
        // Test-only dependency that makes deleting i-stuck's enrollment fail, after its usage events were already deleted.
        jdbc.execute("CREATE TABLE purge_blocker (enrollment_id VARCHAR(255) REFERENCES enrollments (id))");
        jdbc.update("INSERT INTO purge_blocker VALUES ('enr-i-stuck')");
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TABLE purge_blocker");
        for (String table : List.of("classroom_usage_events", "enrollments", "terms", "class_sections")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM users WHERE id IN ('i-teacher', 'u-i-good', 'u-i-stuck')");
    }

    @Test
    void aFailingTermRollsBackAloneAndIsRetriedLater() {
        TermRetentionPurgeService service = new TermRetentionPurgeService(dataSource, transactionManager, new ClassroomProperties(),
                Clock.fixed(Instant.parse("2028-01-20T00:00:00Z"), ZoneOffset.UTC));

        TermRetentionPurgeService.RunResult result = service.runOnce();

        assertEquals(List.of("i-good"), result.purgedTerms().stream().map(TermRetentionPurgeService.TermPurge::termId).toList());
        assertEquals(List.of("i-stuck"), result.failedTermIds());
        assertEquals("PURGED", jdbc.queryForObject("SELECT status FROM terms WHERE id = 'i-good'", String.class));
        // The stuck term is untouched: its earlier deletes rolled back, it is not marked, and it stays eligible.
        assertEquals("ENDED", jdbc.queryForObject("SELECT status FROM terms WHERE id = 'i-stuck'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM classroom_usage_events WHERE term_id = 'i-stuck'", Integer.class));
        assertEquals(List.of("i-stuck"), service.eligibleTermIds());
    }
}
