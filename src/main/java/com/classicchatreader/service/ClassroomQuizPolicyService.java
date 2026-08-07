package com.classicchatreader.service;

import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Enforces assignment quiz pass rules (min correct + max retries) at grade time.
 */
@Service
public class ClassroomQuizPolicyService {

    private final AssignmentRepository assignmentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final QuizAttemptRepository quizAttemptRepository;

    public ClassroomQuizPolicyService(
            AssignmentRepository assignmentRepository,
            EnrollmentRepository enrollmentRepository,
            QuizAttemptRepository quizAttemptRepository) {
        this.assignmentRepository = assignmentRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.quizAttemptRepository = quizAttemptRepository;
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
        Set<String> termIds = activeStudentTermIds(userId);
        if (termIds.isEmpty()) {
            return Optional.empty();
        }
        List<AssignmentEntity> matching = assignmentRepository
                .findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(chapterId, "PUBLISHED")
                .stream()
                .filter(a -> termIds.contains(a.getTermId()))
                .filter(a -> a.getQuizPassMinCorrect() != null && a.getQuizMaxRetries() != null)
                .toList();
        if (matching.isEmpty()) {
            return Optional.empty();
        }

        long usedLong = quizAttemptRepository.countByChapterIdAndUserId(chapterId, userId);
        int used = usedLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) usedLong;

        return matching.stream()
                .map(a -> {
                    int allowed = 1 + a.getQuizMaxRetries();
                    int remaining = Math.max(0, allowed - used);
                    return new AttemptBudget(a, used, allowed, remaining, a.getQuizPassMinCorrect());
                })
                .min(Comparator.comparingInt(AttemptBudget::attemptsRemaining)
                        .thenComparingInt(AttemptBudget::passMinCorrect));
    }

    private Set<String> activeStudentTermIds(String userId) {
        List<EnrollmentEntity> enrollments = enrollmentRepository
                .findByUserIdAndStatusAndDeletedAtIsNull(userId, "ACTIVE");
        Set<String> termIds = new HashSet<>();
        for (EnrollmentEntity enrollment : enrollments) {
            if (enrollment.getTermId() != null) {
                termIds.add(enrollment.getTermId());
            }
        }
        return termIds;
    }
}
