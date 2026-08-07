package com.classicchatreader.service;

import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

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

    private ClassroomQuizPolicyService service;

    @BeforeEach
    void setUp() {
        service = new ClassroomQuizPolicyService(
                assignmentRepository, enrollmentRepository, quizAttemptRepository);
    }

    @Test
    void assertCanAttempt_allowsWhenBudgetRemains() {
        EnrollmentEntity enrollment = new EnrollmentEntity();
        enrollment.setTermId("term-1");
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setTermId("term-1");
        assignment.setChapterId("chapter-1");
        assignment.setQuizRequired(true);
        assignment.setQuizPassMinCorrect(7);
        assignment.setQuizMaxRetries(1);

        when(enrollmentRepository.findByUserIdAndStatusAndDeletedAtIsNull("user-1", "ACTIVE"))
                .thenReturn(List.of(enrollment));
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(assignment));
        when(quizAttemptRepository.countByChapterIdAndUserId("chapter-1", "user-1")).thenReturn(1L);

        assertDoesNotThrow(() -> service.assertCanAttempt("chapter-1", "user-1"));
    }

    @Test
    void assertCanAttempt_rejectsWhenBudgetExhausted() {
        EnrollmentEntity enrollment = new EnrollmentEntity();
        enrollment.setTermId("term-1");
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setTermId("term-1");
        assignment.setChapterId("chapter-1");
        assignment.setQuizRequired(true);
        assignment.setQuizPassMinCorrect(7);
        assignment.setQuizMaxRetries(0);

        when(enrollmentRepository.findByUserIdAndStatusAndDeletedAtIsNull("user-1", "ACTIVE"))
                .thenReturn(List.of(enrollment));
        when(assignmentRepository.findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                "chapter-1", "PUBLISHED")).thenReturn(List.of(assignment));
        when(quizAttemptRepository.countByChapterIdAndUserId("chapter-1", "user-1")).thenReturn(1L);

        assertThrows(ResponseStatusException.class, () -> service.assertCanAttempt("chapter-1", "user-1"));
    }
}
