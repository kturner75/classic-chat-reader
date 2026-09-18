package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.ChatExportJobEntity;
import com.classicchatreader.entity.EducationRecordAccessLogEntity;
import com.classicchatreader.repository.ChatExportJobRepository;
import com.classicchatreader.repository.EducationRecordAccessLogRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** Real schema and transactions: the job row and its audit row commit together, or not at all. */
@DataJpaTest
@Import({TeacherChatExportService.class, EducationRecordAccessLogService.class, ClassroomProperties.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeacherChatExportIntegrationTest {

    @Autowired private TeacherChatExportService service;
    @Autowired private ChatExportJobRepository jobs;
    @Autowired private EducationRecordAccessLogRepository accessLogs;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @MockitoBean private ClassroomAuthorizationService authorization;
    @MockitoSpyBean private EducationRecordAccessLogService accessLogService;

    private void sql(String statement) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> entityManager.createNativeQuery(statement).executeUpdate());
    }

    @BeforeEach
    void seed() {
        sql("INSERT INTO users (id, email, created_at, updated_at) VALUES ('ex-teacher', 'ex-teacher@example.test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        sql("INSERT INTO users (id, email, created_at, updated_at) VALUES ('ex-student', 'ex-student@example.test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        sql("INSERT INTO class_sections (id, owner_user_id, name, status, created_at, updated_at) VALUES ('ex-class', 'ex-teacher', 'English 101', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        sql("INSERT INTO terms (id, class_section_id, name, status, start_date, end_date, created_at, updated_at) VALUES ('ex-term', 'ex-class', 'Fall', 'ACTIVE', DATE '2026-08-24', DATE '2026-12-12', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        sql("INSERT INTO enrollments (id, term_id, user_id, role, status, joined_date, created_at, updated_at) VALUES ('ex-enrollment', 'ex-term', 'ex-student', 'STUDENT', 'COMPLETED', DATE '2026-08-24', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        sql("INSERT INTO books (id, source, source_id, title, author) VALUES ('book-x', 'gutenberg', 'ex-1342', 'Pride and Prejudice', 'Austen, Jane')");
        sql("INSERT INTO reading_buddy_messages (id, owner_key, book_id, persona_id, role, content, kind, chapter_index, paragraph_index, content_hash, created_at, chronology_sequence) "
                + "VALUES ('ex-msg', 'user:ex-student', 'book-x', 'sage', 'user', 'Why is Darcy rude?', 'chat', 1, 0, 'hash', TIMESTAMP '2026-09-01 10:00:00', 1)");
        when(authorization.canManageTerm("ex-teacher", "ex-term")).thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        for (String table : List.of("education_record_access_logs", "chat_export_jobs", "reading_buddy_messages", "enrollments", "terms", "class_sections")) {
            sql("DELETE FROM " + table);
        }
        sql("DELETE FROM books WHERE id = 'book-x'");
        sql("DELETE FROM users WHERE id IN ('ex-teacher', 'ex-student')");
    }

    @Test
    void exportForACompletedEnrollmentPersistsTheJobAndAnAuditRowPointingAtIt() {
        TeacherChatExportService.ExportFile file = service.exportReadingBuddy("ex-teacher", "ex-term", "ex-student", "json", null);

        assertEquals(1, file.messageCount());
        List<ChatExportJobEntity> saved = jobs.findBySubjectUserIdOrderByCreatedAtDesc("ex-student");
        assertEquals(1, saved.size());
        assertEquals(file.jobId(), saved.getFirst().getId());
        assertEquals("READY", saved.getFirst().getStatus());
        assertNotNull(saved.getFirst().getCompletedAt());
        assertNull(saved.getFirst().getArtifactStorageKey());
        List<EducationRecordAccessLogEntity> audit = accessLogs.findBySubjectUserIdOrderByOccurredAtDesc("ex-student");
        assertEquals(1, audit.size());
        assertEquals("EXPORT_CHAT", audit.getFirst().getAccessType());
        assertEquals("CHAT_EXPORT_JOB", audit.getFirst().getResourceType());
        assertEquals(file.jobId(), audit.getFirst().getResourceId());
    }

    @Test
    void failedAuditWriteLeavesNoExportJobBehind() {
        doThrow(new IllegalStateException("audit unavailable")).when(accessLogService)
                .recordAccess(any(), any(String.class), any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> service.exportReadingBuddy("ex-teacher", "ex-term", "ex-student", "json", null));
        assertTrue(jobs.findBySubjectUserIdOrderByCreatedAtDesc("ex-student").isEmpty());
        assertTrue(accessLogs.findBySubjectUserIdOrderByOccurredAtDesc("ex-student").isEmpty());
    }
}
