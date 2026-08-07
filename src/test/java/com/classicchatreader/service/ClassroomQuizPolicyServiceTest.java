package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.EnrollmentEntity;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setTermId("term-1");
        assignment.setChapterId("chapter-1");
        assignment.setQuizRequired(true);
        assignment.setQuizPassMinCorrect(7);
        assignment.setQuizMaxRetries(1);

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(quizAttemptRepository.findMaxCorrectAnswersByChapterIdAndUserId("chapter-1", "user-1"))
                .thenReturn(0);
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(assignment));
        when(quizAttemptRepository.countByChapterIdAndUserId("chapter-1", "user-1")).thenReturn(1L);

        assertDoesNotThrow(() -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    @Test
    void assertCanAttempt_rejectsWhenBudgetExhausted() {
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setTermId("term-1");
        assignment.setChapterId("chapter-1");
        assignment.setQuizRequired(true);
        assignment.setQuizPassMinCorrect(7);
        assignment.setQuizMaxRetries(0);

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(quizAttemptRepository.findMaxCorrectAnswersByChapterIdAndUserId("chapter-1", "user-1"))
                .thenReturn(0);
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(assignment));
        when(quizAttemptRepository.countByChapterIdAndUserId("chapter-1", "user-1")).thenReturn(1L);

        assertThrows(ResponseStatusException.class, () -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    @Test
    void assertCanAttempt_ignoresFutureAvailableFromAssignments() {
        AssignmentEntity future = new AssignmentEntity();
        future.setTermId("term-1");
        future.setChapterId("chapter-1");
        future.setQuizRequired(true);
        future.setQuizPassMinCorrect(7);
        future.setQuizMaxRetries(0);
        future.setAvailableFromDate(LocalDate.of(2026, 9, 1));

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(quizAttemptRepository.findMaxCorrectAnswersByChapterIdAndUserId("chapter-1", "user-1"))
                .thenReturn(0);
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(future));

        assertDoesNotThrow(() -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    @Test
    void assertCanAttempt_ignoresOtherTermExhaustedBudgets() {
        AssignmentEntity otherTerm = new AssignmentEntity();
        otherTerm.setTermId("term-other");
        otherTerm.setChapterId("chapter-1");
        otherTerm.setQuizRequired(true);
        otherTerm.setQuizPassMinCorrect(7);
        otherTerm.setQuizMaxRetries(0);

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(quizAttemptRepository.findMaxCorrectAnswersByChapterIdAndUserId("chapter-1", "user-1"))
                .thenReturn(0);
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(otherTerm));

        assertDoesNotThrow(() -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    @Test
    void assertCanAttempt_ignoresAlreadyPassedAssignments() {
        AssignmentEntity passed = new AssignmentEntity();
        passed.setTermId("term-1");
        passed.setChapterId("chapter-1");
        passed.setQuizRequired(true);
        passed.setQuizPassMinCorrect(3);
        passed.setQuizMaxRetries(0);

        AssignmentEntity open = new AssignmentEntity();
        open.setTermId("term-1");
        open.setChapterId("chapter-1");
        open.setQuizRequired(true);
        open.setQuizPassMinCorrect(5);
        open.setQuizMaxRetries(2);

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(quizAttemptRepository.findMaxCorrectAnswersByChapterIdAndUserId("chapter-1", "user-1"))
                .thenReturn(4); // passed the first assignment, not the second
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(passed, open));
        when(quizAttemptRepository.countByChapterIdAndUserId("chapter-1", "user-1")).thenReturn(1L);

        assertDoesNotThrow(() -> service.assertCanAttempt("chapter-1", "user-1"));
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
