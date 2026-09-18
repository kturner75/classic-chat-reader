package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.EducationRecordAccessLogEntity;
import com.classicchatreader.repository.EducationRecordAccessLogRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the audit row survives a rollback of the caller's transaction on both entry points
 * (review finding: self-invocation skipped REQUIRES_NEW on the single-subject overload).
 */
@DataJpaTest
@Import({EducationRecordAccessLogService.class, ClassroomProperties.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EducationRecordAccessLogTransactionTest {

    @Autowired private EducationRecordAccessLogService service;
    @Autowired private EducationRecordAccessLogRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void sql(String statement) {
        tx().executeWithoutResult(status -> entityManager.createNativeQuery(statement).executeUpdate());
    }

    @BeforeEach
    void users() {
        sql("INSERT INTO users (id, email, created_at, updated_at) VALUES ('tx-teacher', 'tx-teacher@example.test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        sql("INSERT INTO users (id, email, created_at, updated_at) VALUES ('tx-student', 'tx-student@example.test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    @AfterEach
    void cleanup() {
        sql("DELETE FROM education_record_access_logs");
        sql("DELETE FROM users WHERE id IN ('tx-teacher', 'tx-student')");
    }

    @Test
    void singleSubjectAccessStaysRecordedWhenTheCallerRollsBack() {
        tx().executeWithoutResult(status -> {
            service.recordAccess("tx-teacher", "tx-student", null,
                    EducationRecordAccessLogEntity.ACCESS_VIEW_STUDENT_OVERVIEW, null, null, null);
            status.setRollbackOnly();
        });
        assertEquals(1, repository.findBySubjectUserIdOrderByOccurredAtDesc("tx-student").size());
    }

    @Test
    void multiSubjectAccessStaysRecordedWhenTheCallerRollsBack() {
        tx().executeWithoutResult(status -> {
            service.recordAccess("tx-teacher", List.of("tx-student"), null,
                    EducationRecordAccessLogEntity.ACCESS_VIEW_ROSTER, null, null, null);
            status.setRollbackOnly();
        });
        assertEquals(1, repository.findBySubjectUserIdOrderByOccurredAtDesc("tx-student").size());
    }
}
