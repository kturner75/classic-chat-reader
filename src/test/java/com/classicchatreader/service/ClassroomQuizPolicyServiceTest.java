package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.model.ClassroomContextResponse.ClassroomFeatureStates;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
    private UserRepository userRepository;
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
                userRepository,
                classroomProperties,
                classroomContextService);
    }

    @Test
    void assertCanAttempt_isNoOpForChapterRecapQuizzes() {
        assertDoesNotThrow(() -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    @Test
    void assertCanAttemptAssignment_allowsWhenBudgetRemains() {
        AssignmentEntity assignment = assignment("asg-1", 7, 1);

        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(userRepository.findByIdForUpdate("user-1")).thenReturn(Optional.empty());
        when(quizAttemptRepository.countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("asg-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(1L);

        assertDoesNotThrow(() -> service.assertCanAttemptAssignment("asg-1", "user-1"));
        verify(userRepository).findByIdForUpdate("user-1");
    }

    @Test
    void assertCanAttemptAssignment_rejectsWhenBudgetExhausted() {
        AssignmentEntity assignment = assignment("asg-1", 7, 0);

        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(userRepository.findByIdForUpdate("user-1")).thenReturn(Optional.empty());
        when(quizAttemptRepository.countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("asg-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(1L);

        assertThrows(ResponseStatusException.class, () -> service.assertCanAttemptAssignment("asg-1", "user-1"));
    }

    @Test
    void assertCanAttemptAssignment_allowsPassingScoreWhenRetriesRemain() {
        AssignmentEntity passed = assignment("asg-1", 3, 1);

        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(passed));
        when(userRepository.findByIdForUpdate("user-1")).thenReturn(Optional.empty());
        when(quizAttemptRepository.countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("asg-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(1L);
        when(quizAttemptRepository.findMaxScorePercentByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("asg-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(80);

        assertDoesNotThrow(() -> service.assertCanAttemptAssignment("asg-1", "user-1"));
    }

    @Test
    void assertCanAttemptAssignment_rejectsPerfectScoreEvenWhenRetriesRemain() {
        AssignmentEntity passed = assignment("asg-1", 3, 2);

        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(passed));
        when(userRepository.findByIdForUpdate("user-1")).thenReturn(Optional.empty());
        when(quizAttemptRepository.countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("asg-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(1L);
        when(quizAttemptRepository.findMaxScorePercentByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("asg-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(100);

        assertThrows(ResponseStatusException.class, () -> service.assertCanAttemptAssignment("asg-1", "user-1"));
    }

    @Test
    void resolveStrictestBudget_usesChapterSourceAssignmentsOnly() {
        AssignmentEntity chapterSource = assignment("asg-1", 7, 0);
        chapterSource.setQuizSource(AssignmentEntity.QUIZ_SOURCE_CHAPTER);
        AssignmentEntity custom = assignment("asg-2", 1, 0);
        custom.setQuizSource(AssignmentEntity.QUIZ_SOURCE_CUSTOM);

        when(classroomContextService.getContext("user-1")).thenReturn(enrolled("term-1"));
        when(userRepository.findByIdForUpdate("user-1")).thenReturn(Optional.empty());
        when(classroomProperties.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(assignmentRepository.findByTermIdAndContainedChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "term-1", "chapter-1", "PUBLISHED")).thenReturn(List.of(chapterSource, custom));
        when(quizAttemptRepository.countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("asg-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(1L);

        assertDoesNotThrow(() -> service.resolveStrictestBudget("chapter-1", "user-1"));
    }

    private static AssignmentEntity assignment(String id, int minCorrect, int maxRetries) {
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setId(id);
        assignment.setTermId("term-1");
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
