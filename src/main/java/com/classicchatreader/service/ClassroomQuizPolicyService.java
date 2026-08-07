package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Enforces assignment quiz pass rules (min correct + max retries) at grade time.
 */
@Service
public class ClassroomQuizPolicyService {

    private final AssignmentRepository assignmentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final ClassroomProperties classroomProperties;
    private final ClassroomContextService classroomContextService;

    public ClassroomQuizPolicyService(
            AssignmentRepository assignmentRepository,
            EnrollmentRepository enrollmentRepository,
            QuizAttemptRepository quizAttemptRepository,
            ClassroomProperties classroomProperties,
            ClassroomContextService classroomContextService) {
        this.assignmentRepository = assignmentRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.classroomProperties = classroomProperties;
        this.classroomContextService = classroomContextService;
    }

    public record AttemptBudget(
            AssignmentEntity assignment,
            int attemptsUsed,
            int attemptsAllowed,
            int attemptsRemaining,
            int passMinCorrect
    ) {
    }

    /**
     * When the user has a published quiz-required assignment with pass rules for this chapter,
     * reject grading if the attempt budget is exhausted.
     */
    public void assertCanAttempt(String chapterId, String userId) {
        if (userId == null || userId.isBlank() || chapterId == null || chapterId.isBlank()) {
            return;
        }
        Optional<AttemptBudget> budget = resolveStrictestBudget(chapterId, userId);
        if (budget.isEmpty()) {
            return;
        }
        if (budget.get().attemptsRemaining() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No quiz attempts remaining for this assignment. "
                            + "Required score: " + budget.get().passMinCorrect()
                            + " correct; attempts used: " + budget.get().attemptsUsed()
                            + "/" + budget.get().attemptsAllowed() + ".");
        }
    }

    public Optional<AttemptBudget> resolveStrictestBudget(String chapterId, String userId) {
        String activeTermId = resolveActiveStudentTermId(userId);
        if (activeTermId == null) {
            return Optional.empty();
        }
        LocalDate today = classroomProperties.today();
        List<AssignmentEntity> matching = assignmentRepository
                .findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNullForUpdate(chapterId, "PUBLISHED")
                .stream()
                .filter(a -> activeTermId.equals(a.getTermId()))
                .filter(a -> a.getQuizPassMinCorrect() != null && a.getQuizMaxRetries() != null)
                // Match student classroom context: ignore not-yet-available assignments.
                .filter(a -> a.getAvailableFromDate() == null || !a.getAvailableFromDate().isAfter(today))
                .toList();
        if (matching.isEmpty()) {
            return Optional.empty();
        }

        return matching.stream()
                .map(a -> toBudget(a, chapterId, userId))
                // Already-passed assignments should not block retries for other open ones.
                .filter(b -> b != null)
                .min(Comparator.comparingInt(AttemptBudget::attemptsRemaining)
                        .thenComparingInt(AttemptBudget::passMinCorrect));
    }

    private AttemptBudget toBudget(AssignmentEntity assignment, String chapterId, String userId) {
        LocalDateTime since = attemptWindowStart(assignment);
        long usedLong = quizAttemptRepository.countByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                chapterId, userId, since);
        int used = usedLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) usedLong;
        int best = quizAttemptRepository.findMaxCorrectAnswersByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                chapterId, userId, since);
        int minCorrect = assignment.getQuizPassMinCorrect();
        if (best >= minCorrect) {
            return null;
        }
        int allowed = 1 + assignment.getQuizMaxRetries();
        int remaining = Math.max(0, allowed - used);
        return new AttemptBudget(assignment, used, allowed, remaining, minCorrect);
    }

    private LocalDateTime attemptWindowStart(AssignmentEntity assignment) {
        LocalDateTime since = assignment.getQuizRulesActivatedAt();
        if (since == null) {
            since = assignment.getCreatedAt();
        }
        if (assignment.getAvailableFromDate() != null) {
            LocalDateTime open = LocalDateTime.of(
                    assignment.getAvailableFromDate(), LocalTime.MIN);
            if (since == null || open.isAfter(since)) {
                since = open;
            }
        }
        return since != null ? since : LocalDateTime.of(1970, 1, 1, 0, 0);
    }

    private String resolveActiveStudentTermId(String userId) {
        ClassroomContextResponse context = classroomContextService.getContext(userId);
        if (context != null
                && context.enrolled()
                && "STUDENT".equalsIgnoreCase(context.role())
                && context.termId() != null
                && !context.termId().isBlank()) {
            return context.termId();
        }
        // Fallback only when context is unavailable: single ACTIVE enrollment.
        List<EnrollmentEntity> enrollments = enrollmentRepository
                .findByUserIdAndStatusAndDeletedAtIsNull(userId, "ACTIVE");
        if (enrollments.size() == 1 && enrollments.get(0).getTermId() != null) {
            return enrollments.get(0).getTermId();
        }
        return null;
    }
}
