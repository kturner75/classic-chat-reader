package com.classicchatreader.service;

import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.AssignmentProgressEntity;
import com.classicchatreader.entity.ClassroomUsageEventEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.repository.AssignmentProgressRepository;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.ClassroomUsageEventRepository;
import com.classicchatreader.repository.TermRepository;
import com.classicchatreader.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassroomUsageServiceTest {

    @Mock private ClassroomAuthorizationService authorizationService;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private AssignmentProgressRepository assignmentProgressRepository;
    @Mock private ClassroomUsageEventRepository usageEventRepository;
    @Mock private TermRepository termRepository;
    @Mock private UserRepository userRepository;

    private ClassroomUsageService service;

    @BeforeEach
    void setUp() {
        service = new ClassroomUsageService(
                authorizationService,
                assignmentRepository,
                assignmentProgressRepository,
                usageEventRepository,
                termRepository,
                userRepository
        );
    }

    @Test
    void markOpenedIsIdempotentForStudent() {
        when(userRepository.existsById("student-1")).thenReturn(true);
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setId("a-1");
        assignment.setTermId("term-1");
        assignment.setBookId("book-1");
        assignment.setStatus("PUBLISHED");
        when(assignmentRepository.findByIdAndDeletedAtIsNull("a-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.isActiveStudentOnTerm("student-1", "term-1")).thenReturn(true);

        AssignmentProgressEntity existing = new AssignmentProgressEntity();
        existing.setAssignmentId("a-1");
        existing.setFirstOpenedAt(java.time.LocalDateTime.of(2026, 8, 10, 12, 0));
        when(assignmentProgressRepository.findByAssignmentIdAndUserId("a-1", "student-1"))
                .thenReturn(Optional.of(existing));

        ClassroomUsageService.OpenedResult result = service.markAssignmentOpened("student-1", "a-1");
        assertTrue(result.opened());
        assertFalse(result.newlyOpened());
        verify(assignmentProgressRepository, never()).saveAndFlush(any());
    }

    @Test
    void markOpenedCreatesProgressForEnrolledStudent() {
        when(userRepository.existsById("student-1")).thenReturn(true);
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setId("a-1");
        assignment.setTermId("term-1");
        assignment.setBookId("book-1");
        assignment.setStatus("PUBLISHED");
        when(assignmentRepository.findByIdAndDeletedAtIsNull("a-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.isActiveStudentOnTerm("student-1", "term-1")).thenReturn(true);
        when(assignmentProgressRepository.findByAssignmentIdAndUserId("a-1", "student-1"))
                .thenReturn(Optional.empty());
        when(assignmentProgressRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        TermEntity term = new TermEntity();
        term.setId("term-1");
        term.setClassSectionId("section-1");
        when(termRepository.findByIdAndDeletedAtIsNull("term-1")).thenReturn(Optional.of(term));

        ClassroomUsageService.OpenedResult result = service.markAssignmentOpened("student-1", "a-1");
        assertTrue(result.newlyOpened());
        assertTrue(result.opened());
        verify(assignmentProgressRepository).saveAndFlush(any());
        verify(usageEventRepository).save(any(ClassroomUsageEventEntity.class));
    }

    @Test
    void heartbeatClampsDurationAndRequiresEnrollment() {
        when(userRepository.existsById("student-1")).thenReturn(true);
        when(authorizationService.isActiveStudentOnTerm("student-1", "term-1")).thenReturn(true);
        TermEntity term = new TermEntity();
        term.setId("term-1");
        term.setClassSectionId("section-1");
        when(termRepository.findByIdAndDeletedAtIsNull("term-1")).thenReturn(Optional.of(term));
        when(usageEventRepository.saveAndFlush(any())).thenAnswer(inv -> {
            ClassroomUsageEventEntity event = inv.getArgument(0);
            event.setId("evt-1");
            return event;
        });

        ClassroomUsageService.HeartbeatResult result = service.recordReadingHeartbeat(
                "student-1",
                new ClassroomUsageService.HeartbeatRequest(
                        "term-1", "book-1", "ch-1", "a-1", 999_999L, "sess-1", "idem-1"));

        assertEquals(ClassroomUsageService.MAX_HEARTBEAT_DURATION_MS, result.acceptedDurationMs());
        ArgumentCaptor<ClassroomUsageEventEntity> captor = ArgumentCaptor.forClass(ClassroomUsageEventEntity.class);
        verify(usageEventRepository).saveAndFlush(captor.capture());
        assertEquals(ClassroomUsageEventEntity.TYPE_READING_HEARTBEAT, captor.getValue().getEventType());
        assertEquals(ClassroomUsageService.MAX_HEARTBEAT_DURATION_MS, captor.getValue().getDurationMs());
    }

    @Test
    void heartbeatForbiddenWithoutEnrollment() {
        when(userRepository.existsById("student-1")).thenReturn(true);
        when(authorizationService.isActiveStudentOnTerm("student-1", "term-1")).thenReturn(false);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.recordReadingHeartbeat(
                        "student-1",
                        new ClassroomUsageService.HeartbeatRequest(
                                "term-1", "book-1", null, null, 5000L, null, null))
        );
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }
}
