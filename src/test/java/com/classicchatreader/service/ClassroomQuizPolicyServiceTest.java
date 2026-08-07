package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.model.ClassroomContextResponse.ClassroomFeatureStates;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassroomQuizPolicyServiceTest {

    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private EnrollmentRepository enrollmentRepository;
    @Mock
    private QuizAttemptRepository quizAttemptRepository;
    @Mock
    private ClassroomProperties classroomProperties;
    @Mock
    private ClassroomContextService classroomContextService;

    private ClassroomQuizPolicyService service;

    @BeforeEach
    void setUp() {
        service = new ClassroomQuizPolicyService(
                assignmentRepository,
                enrollmentRepository,
                quizAttemptRepository,
                classroomProperties,
                classroomContextService);
    }

    @Test
    void assertCanAttempt_allowsWhenBudgetRemains() {
        AssignmentEntity assignment = assignment("term-1", 7, 1);

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(assignment));
        when(quizAttemptRepository.countByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                eq("chapter-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(1L);
        when(quizAttemptRepository.findMaxCorrectAnswersByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                eq("chapter-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(0);

        assertDoesNotThrow(() -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    @Test
    void assertCanAttempt_rejectsWhenBudgetExhausted() {
        AssignmentEntity assignment = assignment("term-1", 7, 0);

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(assignment));
        when(quizAttemptRepository.countByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                eq("chapter-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(1L);
        when(quizAttemptRepository.findMaxCorrectAnswersByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                eq("chapter-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(0);

        assertThrows(ResponseStatusException.class, () -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    @Test
    void assertCanAttempt_ignoresFutureAvailableFromAssignments() {
        AssignmentEntity future = assignment("term-1", 7, 0);
        future.setAvailableFromDate(LocalDate.of(2026, 9, 1));

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(future));

        assertDoesNotThrow(() -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    @Test
    void assertCanAttempt_ignoresOtherTermExhaustedBudgets() {
        AssignmentEntity otherTerm = assignment("term-other", 7, 0);

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(otherTerm));

        assertDoesNotThrow(() -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    @Test
    void assertCanAttempt_ignoresAlreadyPassedAssignments() {
        AssignmentEntity passed = assignment("term-1", 3, 0);
        AssignmentEntity open = assignment("term-1", 5, 2);

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(passed, open));
        when(quizAttemptRepository.countByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                eq("chapter-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(1L);
        when(quizAttemptRepository.findMaxCorrectAnswersByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                eq("chapter-1"), eq("user-1"), any(LocalDateTime.class)))
                .thenReturn(4); // passed first assignment threshold, not second

        assertDoesNotThrow(() -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    private static AssignmentEntity assignment(String termId, int minCorrect, int maxRetries) {
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setTermId(termId);
        assignment.setChapterId("chapter-1");
        assignment.setQuizRequired(true);
        assignment.setQuizPassMinCorrect(minCorrect);
        assignment.setQuizMaxRetries(maxRetries);
        assignment.setCreatedAt(LocalDateTime.of(2026, 8, 1, 12, 0));
        return assignment;
    }

    private static ClassroomContextResponse enrolled(String termId) {
        return new ClassroomContextResponse(
                true,
                "class-1",
                "Class",
                "Teacher",
                ClassroomFeatureStates.defaults(),
                List.of(),
                termId,
                "STUDENT");
    }
}
