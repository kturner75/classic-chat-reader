package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FERPA term retention purge (BL-043.6).
 *
 * <p>A term is eligible once {@code retention_purge_after} has passed, or, when that is unset, once
 * {@code end_date + classroom.ferpa.term-retain-days} has passed. Terms with no end date never
 * purge. For each eligible term, in its own transaction: delete the term's student records
 * (enrollments, assignment progress, usage events) and mark the term {@code PURGED}.
 *
 * <p>Kept on purpose: student-owned data (Reading Buddy and character chats, quiz history; the
 * student can delete it with their account), teacher content and roles, and the term row itself.
 * Separately, FERPA access-log rows are deleted only after their own {@code retain_until}, and
 * chat export records after the same access-log retention period.
 */
@Service
public class TermRetentionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(TermRetentionPurgeService.class);
    public static final String STATUS_PURGED = "PURGED";

    public record TermPurge(String termId, Map<String, Integer> deletedRows) {}

    public record RunResult(List<TermPurge> purgedTerms, List<String> failedTermIds, int accessLogsDeleted, int exportJobsDeleted) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ClassroomProperties properties;
    private final Clock clock;

    @Autowired
    public TermRetentionPurgeService(DataSource dataSource, PlatformTransactionManager transactionManager, ClassroomProperties properties) {
        this(dataSource, transactionManager, properties, Clock.systemUTC());
    }

    TermRetentionPurgeService(DataSource dataSource, PlatformTransactionManager transactionManager, ClassroomProperties properties, Clock clock) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${classroom.ferpa.purge-cron:0 30 3 * * *}")
    public void scheduledRun() {
        if (!properties.retentionPurgeEnabled()) {
            return;
        }
        RunResult result = runOnce();
        log.info("classroom_purge terms={} failed={} access_logs={} export_jobs={}",
                result.purgedTerms().size(), result.failedTermIds().size(), result.accessLogsDeleted(), result.exportJobsDeleted());
    }

    public List<String> eligibleTermIds() {
        LocalDateTime now = LocalDateTime.now(clock);
        // Calendar dates on terms are school-calendar dates, so the cutoff uses the classroom zone
        // rather than the JVM/UTC date: end_date + N days is before today <=> end_date < today - N
        // (the end day itself still counts as term time).
        LocalDate endedBefore = LocalDate.ofInstant(clock.instant(), properties.calendarZoneId())
                .minusDays(properties.termRetainDays());
        // Soft-deleted terms are purged too: hiding a term must not keep its student records forever.
        return jdbc.queryForList("""
                SELECT id FROM terms
                WHERE status <> :purged
                  AND ((retention_purge_after IS NOT NULL AND retention_purge_after <= :now)
                    OR (retention_purge_after IS NULL AND end_date IS NOT NULL AND end_date < :endedBefore))
                ORDER BY id""",
                new MapSqlParameterSource("purged", STATUS_PURGED).addValue("now", now).addValue("endedBefore", endedBefore),
                String.class);
    }

    /** Purges every eligible term (each in its own transaction), then expired compliance rows. */
    public RunResult runOnce() {
        List<TermPurge> purged = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String termId : eligibleTermIds()) {
            try {
                TermPurge result = transactions.execute(status -> purgeTerm(termId));
                if (result != null) {
                    purged.add(result);
                }
            } catch (RuntimeException e) {
                failed.add(termId);
                log.error("classroom_purge term={} failed; it will be retried on the next run", termId, e);
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // Anything a writer inserted into an already-purged term (an in-flight request that started
        // before the term was marked) is removed on the next run, so nothing outlives retention.
        Integer late = transactions.execute(status -> deleteStudentRecords(
                new MapSqlParameterSource("purged", STATUS_PURGED), " IN (SELECT id FROM terms WHERE status = :purged)").values()
                .stream().mapToInt(Integer::intValue).sum());
        if (late != null && late > 0) {
            log.info("classroom_purge removed {} row(s) written into already-purged terms", late);
        }
        Integer logs = transactions.execute(status -> jdbc.update(
                "DELETE FROM education_record_access_logs WHERE retain_until IS NOT NULL AND retain_until < :now",
                new MapSqlParameterSource("now", now)));
        // expires_at is set when the export is created; older rows without one fall back to age.
        Integer exports = transactions.execute(status -> jdbc.update("""
                DELETE FROM chat_export_jobs
                WHERE (expires_at IS NOT NULL AND expires_at < :now)
                   OR (expires_at IS NULL AND created_at < :cutoff)""",
                new MapSqlParameterSource("now", now)
                        .addValue("cutoff", now.minusDays(properties.accessLogRetainDays()))));
        return new RunResult(purged, failed, logs == null ? 0 : logs, exports == null ? 0 : exports);
    }

    /** The term-scoped student records this job removes; {@code match} selects the term(s). */
    private Map<String, Integer> deleteStudentRecords(MapSqlParameterSource params, String match) {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        for (String table : List.of("assignment_progress", "classroom_usage_events", "enrollments")) {
            deleted.put(table, jdbc.update("DELETE FROM " + table + " WHERE term_id" + match, params));
        }
        return deleted;
    }

    private TermPurge purgeTerm(String termId) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", termId)
                .addValue("purged", STATUS_PURGED)
                .addValue("now", LocalDateTime.now(clock));
        // Re-check inside the transaction: a term marked PURGED since selection is skipped. The row is
        // locked so two runs (or a manual trigger during the nightly run) cannot purge it at once.
        Integer live = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT id FROM terms WHERE id = :t AND status <> :purged FOR UPDATE) locked", p, Integer.class);
        if (live == null || live == 0) {
            return null;
        }
        // Mark PURGED first, in the same transaction: once it commits, ClassroomAuthorizationService
        // sees a non-ACTIVE term and new writers are refused. In-flight writers that already passed
        // their check are cleaned up by the late-write sweep on the next run.
        jdbc.update("""
                UPDATE terms SET status = :purged, retention_purge_after = COALESCE(retention_purge_after, :now), updated_at = :now
                WHERE id = :t""", p);
        Map<String, Integer> deleted = deleteStudentRecords(p, " = :t");
        log.info("classroom_purge term={} deleted={}", termId, deleted);
        return new TermPurge(termId, deleted);
    }

}
