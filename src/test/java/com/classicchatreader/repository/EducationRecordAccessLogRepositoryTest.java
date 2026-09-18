package com.classicchatreader.repository;

import com.classicchatreader.entity.EducationRecordAccessLogEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The V14 table already existed; this pins the entity mapping to it (BL-043.5). */
@DataJpaTest
class EducationRecordAccessLogRepositoryTest {

    @Autowired
    private EducationRecordAccessLogRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private String user(String email) {
        String id = "user-" + email.hashCode();
        entityManager.getEntityManager().createNativeQuery(
                        "INSERT INTO users (id, email, created_at, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                .setParameter(1, id).setParameter(2, email).executeUpdate();
        return id;
    }

    @Test
    void storesHashedAccessRowsQueryableBySubjectAndActor() {
        String teacher = user("teacher@example.test");
        String student = user("student@example.test");
        LocalDateTime occurredAt = LocalDateTime.now(ZoneOffset.UTC);

        repository.save(new EducationRecordAccessLogEntity(teacher, student, null,
                EducationRecordAccessLogEntity.ACCESS_VIEW_STUDENT_OVERVIEW, EducationRecordAccessLogEntity.RESOURCE_TERM,
                "term-1", "ip-hash", "ua-hash", occurredAt, occurredAt.plusDays(2555)));
        repository.save(new EducationRecordAccessLogEntity(teacher, student, null,
                EducationRecordAccessLogEntity.ACCESS_EXPORT_CHAT, null, null, null, null, occurredAt.minusDays(1), null));
        entityManager.flush();
        entityManager.clear();

        List<EducationRecordAccessLogEntity> bySubject = repository.findBySubjectUserIdOrderByOccurredAtDesc(student);
        assertEquals(2, bySubject.size());
        assertEquals(EducationRecordAccessLogEntity.ACCESS_VIEW_STUDENT_OVERVIEW, bySubject.getFirst().getAccessType());
        assertEquals("ip-hash", bySubject.getFirst().getIpHash());
        assertNotNull(bySubject.getFirst().getId());
        assertEquals(2, repository.findByActorUserIdOrderByOccurredAtDesc(teacher).size());
        assertTrue(repository.findBySubjectUserIdOrderByOccurredAtDesc(teacher).isEmpty());
    }

    @Test
    void actorAndSubjectAreRequired() {
        String teacher = user("teacher2@example.test");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        assertThrows(Exception.class, () -> {
            repository.save(new EducationRecordAccessLogEntity(teacher, null, null, "VIEW_ROSTER", null, null, null, null, now, null));
            entityManager.flush();
        });
    }
}
