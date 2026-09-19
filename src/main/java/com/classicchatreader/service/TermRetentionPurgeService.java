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
        // end_date + N days is before today  <=>  end_date < today - N (dates, so the end day itself counts as term time).
        LocalDate endedBefore = LocalDate.now(clock).minusDays(properties.termRetainDays());
        return jdbc.queryForList("""
                SELECT id FROM terms
                WHERE status <> :purged AND deleted_at IS NULL
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
        Integer logs = transactions.execute(status -> jdbc.update(
                "DELETE FROM education_record_access_logs WHERE retain_until IS NOT NULL AND retain_until < :now",
                new MapSqlParameterSource("now", now)));
        Integer exports = transactions.execute(status -> jdbc.update(
                "DELETE FROM chat_export_jobs WHERE created_at < :cutoff",
                new MapSqlParameterSource("cutoff", now.minusDays(properties.accessLogRetainDays()))));
        return new RunResult(purged, failed, logs == null ? 0 : logs, exports == null ? 0 : exports);
    }

    private TermPurge purgeTerm(String termId) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", termId)
                .addValue("purged", STATUS_PURGED)
                .addValue("now", LocalDateTime.now(clock));
        // Re-check inside the transaction: a term marked PURGED or deleted since selection is skipped.
        Integer live = jdbc.queryForObject("SELECT COUNT(*) FROM terms WHERE id = :t AND status <> :purged AND deleted_at IS NULL", p, Integer.class);
        if (live == null || live == 0) {
            return null;
        }
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("assignment_progress", jdbc.update("DELETE FROM assignment_progress WHERE term_id = :t", p));
        deleted.put("classroom_usage_events", jdbc.update("DELETE FROM classroom_usage_events WHERE term_id = :t", p));
        deleted.put("enrollments", jdbc.update("DELETE FROM enrollments WHERE term_id = :t", p));
        jdbc.update("""
                UPDATE terms SET status = :purged, retention_purge_after = COALESCE(retention_purge_after, :now), updated_at = :now
                WHERE id = :t""", p);
        log.info("classroom_purge term={} deleted={}", termId, deleted);
        return new TermPurge(termId, deleted);
    }

}
