package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    private final UserRepository userRepository;
    private final ClassroomProperties classroomProperties;
    private final ClassroomContextService classroomContextService;

    public ClassroomQuizPolicyService(
            AssignmentRepository assignmentRepository,
            EnrollmentRepository enrollmentRepository,
            QuizAttemptRepository quizAttemptRepository,
            UserRepository userRepository,
            ClassroomProperties classroomProperties,
            ClassroomContextService classroomContextService) {
        this.assignmentRepository = assignmentRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.userRepository = userRepository;
        this.classroomProperties = classroomProperties;
        this.classroomContextService = classroomContextService;
    }

    public record AttemptBudget(
            AssignmentEntity assignment,
            int attemptsUsed,
            int attemptsAllowed,
            int attemptsRemaining,
            int passMinCorrect,
            int bestScorePercent
    ) {
    }

    /**
     * Recap / free-reading chapter quizzes do not consume assignment attempt budgets.
     */
    public void assertCanAttempt(String chapterId, String userId) {
        // Intentionally empty: chapter recap attempts are independent of assignment quizzes.
    }

    public void assertCanAttemptAssignment(String assignmentId, String userId) {
        if (userId == null || userId.isBlank() || assignmentId == null || assignmentId.isBlank()) {
            return;
        }
        Optional<AttemptBudget> budget = resolveAssignmentBudget(assignmentId, userId);
        if (budget.isEmpty()) {
            return;
        }
        AttemptBudget resolved = budget.get();
        if (resolved.bestScorePercent() >= 100) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This assignment quiz already has a perfect score.");
        }
        if (resolved.attemptsRemaining() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No quiz attempts remaining for this assignment. "
                            + "Required score: " + resolved.passMinCorrect()
                            + " correct; attempts used: " + resolved.attemptsUsed()
                            + "/" + resolved.attemptsAllowed() + ".");
        }
    }

    public Optional<AttemptBudget> resolveAssignmentBudget(String assignmentId, String userId) {
        AssignmentEntity assignment = assignmentRepository.findByIdAndDeletedAtIsNull(assignmentId).orElse(null);
        if (assignment == null || !assignment.isQuizRequired()) {
            return Optional.empty();
        }
        if (assignment.getQuizPassMinCorrect() == null || assignment.getQuizMaxRetries() == null) {
            return Optional.empty();
        }
        if (userId != null && !userId.isBlank()) {
            userRepository.findByIdForUpdate(userId);
        }
        return Optional.ofNullable(toAssignmentBudget(assignment, userId));
    }

    public Optional<AttemptBudget> resolveStrictestBudget(String chapterId, String userId) {
        String activeTermId = resolveActiveStudentTermId(userId);
        if (activeTermId == null) {
            return Optional.empty();
        }
        if (userId != null && !userId.isBlank()) {
            userRepository.findByIdForUpdate(userId);
        }
        LocalDate today = classroomProperties.today();
        List<AssignmentEntity> matching = assignmentRepository
                .findByTermIdAndContainedChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
                        activeTermId, chapterId, "PUBLISHED")
                .stream()
                .filter(a -> AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(a.getQuizSource()))
                .filter(a -> a.getQuizPassMinCorrect() != null && a.getQuizMaxRetries() != null)
                .filter(a -> a.getAvailableFromDate() == null || !a.getAvailableFromDate().isAfter(today))
                .toList();
        if (matching.isEmpty()) {
            return Optional.empty();
        }

        return matching.stream()
                .map(a -> toAssignmentBudget(a, userId))
                .filter(b -> b != null)
                .min(Comparator.comparingInt(AttemptBudget::attemptsRemaining)
                        .thenComparingInt(AttemptBudget::passMinCorrect));
    }

    private AttemptBudget toAssignmentBudget(AssignmentEntity assignment, String userId) {
        LocalDateTime since = attemptWindowStart(assignment);
        long usedLong = quizAttemptRepository.countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                assignment.getId(), userId, since);
        if (AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(assignment.getQuizSource())
                && assignment.singleChapterId() != null) {
            usedLong += quizAttemptRepository.countByChapterIdAndUserIdAndCreatedAtOnOrAfterExcludingAssignment(
                    assignment.singleChapterId(), userId, since, assignment.getId());
        }
        int used = usedLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) usedLong;
        int bestScorePercent = quizAttemptRepository
                .findMaxScorePercentByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                        assignment.getId(), userId, since);
        if (AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(assignment.getQuizSource())
                && assignment.singleChapterId() != null) {
            bestScorePercent = Math.max(bestScorePercent, quizAttemptRepository
                    .findMaxScorePercentByChapterIdAndUserIdAndCreatedAtOnOrAfterExcludingAssignment(
                            assignment.singleChapterId(), userId, since, assignment.getId()));
        }
        int minCorrect = assignment.getQuizPassMinCorrect();
        int allowed = 1 + assignment.getQuizMaxRetries();
        int remaining = bestScorePercent >= 100 ? 0 : Math.max(0, allowed - used);
        return new AttemptBudget(assignment, used, allowed, remaining, minCorrect, bestScorePercent);
    }

    private LocalDateTime attemptWindowStart(AssignmentEntity assignment) {
        LocalDateTime since = assignment.getQuizRulesActivatedAt();
        if (since == null) {
            since = assignment.getCreatedAt();
        }
        if (assignment.getAvailableFromDate() != null) {
            LocalDateTime open = assignment.getAvailableFromDate()
                    .atStartOfDay(classroomProperties.calendarZoneId())
                    .withZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
            if (since == null || open.isAfter(since)) {
                since = open;
            }
        }
        return since != null ? since : LocalDateTime.of(1970, 1, 1, 0, 0);
    }

    private String resolveActiveStudentTermId(String userId) {
        ClassroomContextResponse context = classroomContextService.getContext(userId);
        if (context != null && context.enrolled()) {
            // Context resolved: only student roles are gated by assignment budgets.
            if ("STUDENT".equalsIgnoreCase(context.role())
                    && context.termId() != null
                    && !context.termId().isBlank()) {
                return context.termId();
            }
            return null;
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
