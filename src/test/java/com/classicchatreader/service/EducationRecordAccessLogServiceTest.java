package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.EducationRecordAccessLogEntity;
import com.classicchatreader.repository.EducationRecordAccessLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class EducationRecordAccessLogServiceTest {

    private final EducationRecordAccessLogRepository repository = mock(EducationRecordAccessLogRepository.class);
    private final ClassroomProperties properties = new ClassroomProperties();
    private final EducationRecordAccessLogService service = new EducationRecordAccessLogService(repository, properties);

    @SuppressWarnings("unchecked")
    private List<EducationRecordAccessLogEntity> saved() {
        ArgumentCaptor<List<EducationRecordAccessLogEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        request.addHeader("User-Agent", "Mozilla/5.0 (Macintosh)");
        return request;
    }

    @Test
    void rosterAccessWritesOneHashedRowPerStudentWithRetention() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        service.recordAccess("teacher-1", List.of("student-1", "student-2"), "term-1",
                EducationRecordAccessLogEntity.ACCESS_VIEW_ROSTER, EducationRecordAccessLogEntity.RESOURCE_TERM, "term-1", request());

        List<EducationRecordAccessLogEntity> rows = saved();
        assertEquals(List.of("student-1", "student-2"), rows.stream().map(EducationRecordAccessLogEntity::getSubjectUserId).toList());
        EducationRecordAccessLogEntity row = rows.getFirst();
        assertEquals("teacher-1", row.getActorUserId());
        assertEquals("term-1", row.getTermId());
        assertEquals("VIEW_ROSTER", row.getAccessType());
        assertEquals("TERM", row.getResourceType());
        assertNotNull(row.getIpHash());
        assertNotNull(row.getUserAgentHash());
        assertFalse(row.getIpHash().contains("203.0.113.5"), "raw IP must not be stored");
        assertFalse(row.getUserAgentHash().contains("Mozilla"), "raw user agent must not be stored");
        assertFalse(row.getOccurredAt().isBefore(before));
        assertEquals(2555, java.time.Duration.between(row.getOccurredAt(), row.getRetainUntil()).toDays());
    }

    @Test
    void selfAccessBlanksAndDuplicatesAreNotLogged() {
        service.recordAccess("student-1", Arrays.asList("student-1", "student-2", "student-2", null, " "), "term-1",
                EducationRecordAccessLogEntity.ACCESS_VIEW_ROSTER, null, null, request());

        List<EducationRecordAccessLogEntity> rows = saved();
        assertEquals(List.of("student-2"), rows.stream().map(EducationRecordAccessLogEntity::getSubjectUserId).toList());
        assertNull(rows.getFirst().getResourceType());
    }

    @Test
    void nothingIsWrittenWhenOnlySelfAccessIsRequested() {
        service.recordAccess("teacher-1", "teacher-1", "term-1", EducationRecordAccessLogEntity.ACCESS_VIEW_STUDENT_OVERVIEW, null, null, request());
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void missingActorOrAccessTypeIsRejectedBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class, () -> service.recordAccess(" ", "student-1", "term-1", "VIEW_ROSTER", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.recordAccess("teacher-1", "student-1", "term-1", " ", null, null, null));
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void retentionDaysComeFromConfigurationAndRejectNonsense() {
        ClassroomProperties.Ferpa ferpa = new ClassroomProperties.Ferpa();
        ferpa.setAccessLogRetainDays(30);
        properties.setFerpa(ferpa);
        service.recordAccess("teacher-1", "student-1", null, EducationRecordAccessLogEntity.ACCESS_EXPORT_CHAT, null, null, null);
        EducationRecordAccessLogEntity row = saved().getFirst();
        assertEquals(30, java.time.Duration.between(row.getOccurredAt(), row.getRetainUntil()).toDays());
        assertNull(row.getTermId());
        assertNull(row.getIpHash());

        ferpa.setAccessLogRetainDays(0);
        assertEquals(2555, properties.accessLogRetainDays());
    }
}
