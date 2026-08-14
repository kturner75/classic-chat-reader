package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentChapterEntity;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.AssignmentProgressEntity;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.entity.QuizAttemptEntity;
import com.classicchatreader.entity.UserEntity;
import com.classicchatreader.entity.UserReaderStateEntity;
import com.classicchatreader.model.AccountStateSnapshot;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.model.ClassroomContextResponse.QuizRequirementStatus;
import com.classicchatreader.repository.AssignmentProgressRepository;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterChatConversationRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.UserReaderStateRepository;
import com.classicchatreader.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pilot teacher→student drill-down (BL-025.10). Teacher-of-term only; not school-admin.
 */
@Service
public class TeacherStudentOverviewService {

    private final ClassroomAuthorizationService authorizationService;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentProgressRepository assignmentProgressRepository;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final UserReaderStateRepository userReaderStateRepository;
    private final CharacterChatConversationRepository characterChatConversationRepository;
    private final ClassroomUsageService classroomUsageService;
    private final ClassroomProperties classroomProperties;
    private final ObjectMapper objectMapper;

    public TeacherStudentOverviewService(
            ClassroomAuthorizationService authorizationService,
            EnrollmentRepository enrollmentRepository,
            UserRepository userRepository,
            AssignmentRepository assignmentRepository,
            AssignmentProgressRepository assignmentProgressRepository,
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            QuizAttemptRepository quizAttemptRepository,
            UserReaderStateRepository userReaderStateRepository,
            CharacterChatConversationRepository characterChatConversationRepository,
            ClassroomUsageService classroomUsageService,
            ClassroomProperties classroomProperties,
            ObjectMapper objectMapper) {
        this.authorizationService = authorizationService;
        this.enrollmentRepository = enrollmentRepository;
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
        this.assignmentProgressRepository = assignmentProgressRepository;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.userReaderStateRepository = userReaderStateRepository;
        this.characterChatConversationRepository = characterChatConversationRepository;
        this.classroomUsageService = classroomUsageService;
        this.classroomProperties = classroomProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public StudentOverviewResponse getOverview(String teacherUserId, String termId, String studentUserId) {
        requireTeacher(teacherUserId, termId);
        EnrollmentEntity enrollment = enrollmentRepository
                .findByTermIdAndUserIdAndDeletedAtIsNull(termId, studentUserId)
                .filter(e -> ClassroomAuthorizationService.STATUS_ACTIVE.equals(e.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found on roster."));

        UserEntity student = userRepository.findById(studentUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found on roster."));

        List<AssignmentEntity> published = assignmentRepository
                .findByTermIdAndStatusAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(termId, "PUBLISHED");

        Map<String, AssignmentProgressEntity> openedByAssignment = new HashMap<>();
        for (AssignmentProgressEntity progress : assignmentProgressRepository.findByTermIdAndUserId(termId, studentUserId)) {
            openedByAssignment.put(progress.getAssignmentId(), progress);
        }

        Map<String, AccountStateSnapshot.BookActivity> bookActivity = loadBookActivity(studentUserId);
        Map<String, Long> timeByBook = classroomUsageService.sumApproximateReaderMsByBook(termId, studentUserId);
        long totalTimeMs = classroomUsageService.sumApproximateReaderMs(termId, studentUserId);

        Map<String, BookEntity> booksById = new HashMap<>();
        Set<String> bookIds = new HashSet<>();
        for (AssignmentEntity assignment : published) {
            bookIds.add(assignment.getBookId());
        }
        bookIds.addAll(timeByBook.keySet());
        for (BookEntity book : bookRepository.findAllById(bookIds)) {
            booksById.put(book.getId(), book);
        }

        Map<String, Boolean> characterChatByBook = new HashMap<>();
        List<AssignmentOverview> current = new ArrayList<>();
        List<AssignmentOverview> completed = new ArrayList<>();
        Map<String, BookProgress> progressByBook = new LinkedHashMap<>();
        List<QuizOverview> quizzes = new ArrayList<>();

        for (AssignmentEntity assignment : published) {
            BookEntity book = booksById.get(assignment.getBookId());
            AssignmentChapterEntity first = assignment.firstChapter();
            String chapterId = first == null ? null : first.getChapterId();
            Integer chapterIndex = first == null ? null : first.getChapterIndex();
            String chapterTitle = first == null ? null : chapterRepository.findById(first.getChapterId())
                    .map(ChapterEntity::getTitle)
                    .orElse("Chapter " + Math.max(1, first.getChapterIndex() + 1));
            List<ClassroomContextResponse.AssignmentChapterRef> chapterRefs = assignment.getChapters().stream()
                    .map(row -> new ClassroomContextResponse.AssignmentChapterRef(
                            row.getChapterId(),
                            row.getChapterIndex(),
                            chapterRepository.findById(row.getChapterId())
                                    .map(ChapterEntity::getTitle)
                                    .orElse("Chapter " + Math.max(1, row.getChapterIndex() + 1))))
                    .toList();

            AccountStateSnapshot.BookActivity activity = bookActivity.get(assignment.getBookId());
            boolean characterChatStarted = false;
            if (assignment.isCharacterChatRequired()) {
                characterChatStarted = characterChatByBook.computeIfAbsent(
                        assignment.getBookId(),
                        bookId -> characterChatConversationRepository.countVisibleByUserIdAndBookId(
                                studentUserId, bookId) > 0);
            }

            QuizAttemptSummary quizSummary = resolveQuizAttemptSummary(assignment, studentUserId);
            QuizRequirementStatus quizStatus = resolveQuizStatus(assignment, studentUserId, quizSummary);
            boolean readingComplete = isReadingComplete(assignment, activity, quizStatus);
            boolean quizSatisfied = isQuizSatisfied(assignment, quizStatus);
            boolean characterSatisfied = !assignment.isCharacterChatRequired() || characterChatStarted;
            boolean allDone = readingComplete && quizSatisfied && characterSatisfied;
            boolean readingStarted = hasBookActivity(activity);
            boolean anyStarted = readingStarted
                    || quizStatus == QuizRequirementStatus.COMPLETE
                    || characterChatStarted;

            AssignmentProgressEntity opened = openedByAssignment.get(assignment.getId());
            boolean isOpened = opened != null;
            String statusLabel = allDone ? "Complete" : (anyStarted || isOpened ? "In progress" : "Not started");

            AssignmentOverview overview = new AssignmentOverview(
                    assignment.getId(),
                    assignment.getTitle(),
                    assignment.getBookId(),
                    book != null ? book.getTitle() : "Book unavailable",
                    chapterRefs,
                    chapterId,
                    chapterIndex,
                    chapterTitle,
                    assignment.getDueDate() != null ? assignment.getDueDate().toString() : null,
                    statusLabel,
                    isOpened,
                    opened != null ? opened.getFirstOpenedAt().atOffset(ZoneOffset.UTC).toString() : null,
                    readingComplete,
                    readingStarted,
                    assignment.isQuizRequired(),
                    quizStatus.name(),
                    quizSummary.passed(),
                    quizSummary.attemptsUsed(),
                    quizSummary.attemptsAllowed(),
                    assignment.isCharacterChatRequired(),
                    characterChatStarted
            );
            if (allDone) {
                completed.add(overview);
            } else {
                current.add(overview);
            }

            if (assignment.isQuizRequired()) {
                quizzes.add(buildQuizOverview(
                        assignment, book, chapterId, chapterTitle, quizSummary, quizStatus, studentUserId));
            }

            progressByBook.computeIfAbsent(assignment.getBookId(), bookId ->
                    buildBookProgress(bookId, booksById.get(bookId), bookActivity.get(bookId), timeByBook.get(bookId)));
        }

        // Include books with time but no published assignment (edge case).
        for (Map.Entry<String, Long> entry : timeByBook.entrySet()) {
            progressByBook.computeIfAbsent(entry.getKey(), bookId ->
                    buildBookProgress(bookId, booksById.get(bookId), bookActivity.get(bookId), entry.getValue()));
        }

        List<BookProgress> progressList = new ArrayList<>(progressByBook.values());
        progressList.sort(Comparator.comparing(
                BookProgress::bookTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        quizzes.sort(Comparator
                .comparing(QuizOverview::bookTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(QuizOverview::assignmentTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        List<TimeInBook> timeRows = progressList.stream()
                .filter(row -> row.approximateTimeInReaderMs() != null && row.approximateTimeInReaderMs() > 0)
                .map(row -> new TimeInBook(row.bookId(), row.bookTitle(), row.approximateTimeInReaderMs()))
                .toList();

        return new StudentOverviewResponse(
                termId,
                new StudentIdentity(
                        student.getId(),
                        student.getEmail(),
                        enrollment.getDisplayNameOverride(),
                        enrollment.getJoinedDate() != null ? enrollment.getJoinedDate().toString() : null
                ),
                current,
                completed,
                progressList,
                quizzes,
                new TimeInReaderSummary(
                        "Approximate time in reader",
                        "Engagement proxy from short reader heartbeats — not rigorous proof of attention.",
                        totalTimeMs,
                        timeRows
                ),
                "Pilot teacher drill-down (BL-025.10). Class-scoped teacher authz only; not a school-admin or FERPA-gated broad dashboard rollout."
        );
    }

    private QuizOverview buildQuizOverview(
            AssignmentEntity assignment,
            BookEntity book,
            String chapterId,
            String chapterTitle,
            QuizAttemptSummary quizSummary,
            QuizRequirementStatus quizStatus,
            String studentUserId) {
        LocalDateTime since = attemptWindowStart(assignment);
        int bestScore = quizAttemptRepository
                .findMaxScorePercentByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                        assignment.getId(), studentUserId, since);
        int bestCorrect = quizAttemptRepository
                .findMaxCorrectAnswersByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                        assignment.getId(), studentUserId, since);
        if (AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(assignment.getQuizSource())
                && assignment.singleChapterId() != null) {
            bestScore = Math.max(bestScore, quizAttemptRepository
                    .findMaxUnassignedScorePercentByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                            assignment.singleChapterId(), studentUserId, since));
            bestCorrect = Math.max(bestCorrect, quizAttemptRepository
                    .findMaxUnassignedCorrectAnswersByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                            assignment.singleChapterId(), studentUserId, since));
        }
        List<QuizAttemptEntity> attempts = new java.util.ArrayList<>(quizAttemptRepository
                .findByAssignmentIdAndUserIdOrderByCreatedAtDesc(assignment.getId(), studentUserId));
        if (AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(assignment.getQuizSource())
                && assignment.singleChapterId() != null) {
            for (QuizAttemptEntity chapterAttempt : quizAttemptRepository
                    .findByChapterIdAndUserIdOrderByCreatedAtDesc(assignment.singleChapterId(), studentUserId)) {
                if (chapterAttempt.getAssignmentId() == null) {
                    attempts.add(chapterAttempt);
                }
            }
            attempts.sort((left, right) -> {
                if (left.getCreatedAt() == null) return 1;
                if (right.getCreatedAt() == null) return -1;
                return right.getCreatedAt().compareTo(left.getCreatedAt());
            });
        }
        QuizAttemptEntity latestInWindow = attempts.stream()
                .filter(a -> a.getCreatedAt() != null && !a.getCreatedAt().isBefore(since))
                .findFirst()
                .orElse(null);
        Integer totalQuestions = latestInWindow != null
                ? latestInWindow.getTotalQuestions()
                : attempts.stream().findFirst().map(QuizAttemptEntity::getTotalQuestions).orElse(null);
        String latestAt = latestInWindow != null && latestInWindow.getCreatedAt() != null
                ? latestInWindow.getCreatedAt().atOffset(ZoneOffset.UTC).toString()
                : null;
        Integer attemptsUsed = quizSummary.attemptsUsed();
        boolean complete = quizStatus == QuizRequirementStatus.COMPLETE;
        return new QuizOverview(
                assignment.getBookId(),
                book != null ? book.getTitle() : "Book unavailable",
                chapterId,
                chapterTitle,
                assignment.getId(),
                assignment.getTitle(),
                complete,
                quizStatus.name(),
                quizSummary.passed(),
                attemptsUsed == null ? 0 : attemptsUsed,
                quizSummary.attemptsAllowed(),
                attemptsUsed != null && attemptsUsed > 0 ? Math.max(0, attemptsUsed - 1) : 0,
                bestScore,
                bestCorrect,
                totalQuestions,
                latestAt
        );
    }

    private BookProgress buildBookProgress(
            String bookId,
            BookEntity book,
            AccountStateSnapshot.BookActivity activity,
            Long timeMs) {
        int chapterCount = activity != null && activity.chapterCount() != null && activity.chapterCount() > 0
                ? activity.chapterCount()
                : chapterRepository.findByBookIdOrderByChapterIndex(bookId).size();
        if (chapterCount <= 0) {
            chapterCount = 1;
        }
        Integer reached = maxReachedChapterIndex(activity, chapterCount);
        double maxRatio = activity == null ? 0d : Math.max(
                activity.maxProgressRatio() == null ? 0d : activity.maxProgressRatio(),
                activity.progressRatio() == null ? 0d : activity.progressRatio());
        maxRatio = Math.max(0d, Math.min(1d, maxRatio));
        boolean completed = activity != null && (
                Boolean.TRUE.equals(activity.completed())
                        || activity.completedAt() != null
                        || maxRatio >= 0.999);
        int percent = (int) Math.round(maxRatio * 100d);
        String chapterLabel = reached == null
                ? "—"
                : (reached + 1) + "/" + chapterCount;
        return new BookProgress(
                bookId,
                book != null ? book.getTitle() : "Book unavailable",
                chapterCount,
                reached,
                chapterLabel,
                percent,
                completed,
                activity != null ? activity.lastReadAt() : null,
                timeMs == null ? 0L : timeMs
        );
    }

    private Map<String, AccountStateSnapshot.BookActivity> loadBookActivity(String userId) {
        Optional<UserReaderStateEntity> state = userReaderStateRepository.findById(userId);
        if (state.isEmpty()) {
            return Map.of();
        }
        try {
            AccountStateSnapshot snapshot = objectMapper.readValue(
                    state.get().getStateJson(), AccountStateSnapshot.class);
            if (snapshot == null || snapshot.bookActivity() == null) {
                return Map.of();
            }
            return snapshot.bookActivity();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Optional<ChapterEntity> resolveChapter(String chapterIdRaw, Integer chapterIndex, String bookId) {
        String chapterId = trimToNull(chapterIdRaw);
        if (chapterId != null) {
            return chapterRepository.findByIdWithBook(chapterId)
                    .filter(chapter -> Objects.equals(chapter.getBook().getId(), bookId));
        }
        if (chapterIndex == null) {
            return Optional.empty();
        }
        return chapterRepository.findByBookIdAndChapterIndex(bookId, Math.max(0, chapterIndex));
    }

    private QuizRequirementStatus resolveQuizStatus(
            AssignmentEntity row,
            String userId,
            QuizAttemptSummary attemptSummary) {
        if (!row.isQuizRequired()) {
            return QuizRequirementStatus.NOT_REQUIRED;
        }
        if (row.getQuizPassMinCorrect() == null) {
            if (userId != null && !userId.isBlank()) {
                LocalDateTime since = attemptWindowStart(row);
                long used = quizAttemptRepository.countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                        row.getId(), userId, since);
                if (AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(row.getQuizSource())
                        && row.singleChapterId() != null) {
                    used += quizAttemptRepository.countUnassignedByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                            row.singleChapterId(), userId, since);
                }
                return used > 0
                        ? QuizRequirementStatus.COMPLETE
                        : QuizRequirementStatus.PENDING;
            }
            return QuizRequirementStatus.PENDING;
        }
        if (Boolean.TRUE.equals(attemptSummary.passed())) {
            return QuizRequirementStatus.COMPLETE;
        }
        return QuizRequirementStatus.PENDING;
    }

    private QuizAttemptSummary resolveQuizAttemptSummary(AssignmentEntity row, String userId) {
        if (!row.isQuizRequired() || userId == null || userId.isBlank()) {
            return QuizAttemptSummary.empty();
        }
        Integer minCorrect = row.getQuizPassMinCorrect();
        Integer maxRetries = row.getQuizMaxRetries();
        LocalDateTime since = attemptWindowStart(row);
        long used = quizAttemptRepository.countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                row.getId(), userId, since);
        if (AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(row.getQuizSource())
                && row.singleChapterId() != null) {
            used += quizAttemptRepository.countUnassignedByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                    row.singleChapterId(), userId, since);
        }
        Integer allowed = minCorrect != null && maxRetries != null ? 1 + maxRetries : null;
        Boolean passed = null;
        if (minCorrect != null) {
            int best = quizAttemptRepository.findMaxCorrectAnswersByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                    row.getId(), userId, since);
            if (AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(row.getQuizSource())
                    && row.singleChapterId() != null) {
                best = Math.max(best, quizAttemptRepository
                        .findMaxUnassignedCorrectAnswersByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                                row.singleChapterId(), userId, since));
            }
            passed = best >= minCorrect;
        } else if (used > 0) {
            passed = true;
        }
        return new QuizAttemptSummary(
                used > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) used,
                allowed,
                passed);
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

    private static boolean isQuizSatisfied(AssignmentEntity assignment, QuizRequirementStatus quizStatus) {
        if (!assignment.isQuizRequired()) {
            return true;
        }
        return quizStatus == QuizRequirementStatus.COMPLETE || quizStatus == QuizRequirementStatus.NOT_REQUIRED;
    }

    private static boolean isReadingComplete(
            AssignmentEntity assignment,
            AccountStateSnapshot.BookActivity activity,
            QuizRequirementStatus quizStatus) {
        if (activity != null && (
                Boolean.TRUE.equals(activity.completed())
                        || activity.completedAt() != null
                        || Math.max(
                        activity.maxProgressRatio() == null ? 0d : activity.maxProgressRatio(),
                        activity.progressRatio() == null ? 0d : activity.progressRatio()) >= 0.999)) {
            return true;
        }
        if (!hasBookActivity(activity)) {
            return quizStatus == QuizRequirementStatus.COMPLETE;
        }
        if (assignment.isWholeBook()) {
            return quizStatus == QuizRequirementStatus.COMPLETE;
        }
        int targetIndex = assignment.getChapters().stream()
                .mapToInt(AssignmentChapterEntity::getChapterIndex)
                .max()
                .orElse(-1);
        if (targetIndex < 0) {
            return quizStatus == QuizRequirementStatus.COMPLETE;
        }
        Integer chapterCount = activity.chapterCount() != null && activity.chapterCount() > 0
                ? activity.chapterCount()
                : null;
        Integer reached = maxReachedChapterIndex(activity, chapterCount == null ? 1 : chapterCount);
        if (reached != null && reached >= targetIndex) {
            return true;
        }
        return quizStatus == QuizRequirementStatus.COMPLETE;
    }

    private static boolean hasBookActivity(AccountStateSnapshot.BookActivity activity) {
        if (activity == null) {
            return false;
        }
        if (Boolean.TRUE.equals(activity.completed()) || activity.completedAt() != null) {
            return true;
        }
        if (activity.lastReadAt() != null || activity.lastOpenedAt() != null) {
            return true;
        }
        double progress = Math.max(
                activity.maxProgressRatio() == null ? 0d : activity.maxProgressRatio(),
                activity.progressRatio() == null ? 0d : activity.progressRatio());
        return progress > 0d;
    }

    private static Integer maxReachedChapterIndex(AccountStateSnapshot.BookActivity activity, int chapterCount) {
        if (!hasBookActivity(activity)) {
            return null;
        }
        int n = Math.max(1, chapterCount);
        double maxProgress = Math.max(
                activity.maxProgressRatio() == null ? 0d : activity.maxProgressRatio(),
                0d);
        maxProgress = Math.max(0d, Math.min(1d, maxProgress));
        int fromProgress = -1;
        if (maxProgress > 0d) {
            fromProgress = Math.min(n - 1, Math.max(0, (int) Math.ceil(maxProgress * n - 1e-9) - 1));
        }
        int last = activity.lastChapterIndex() == null ? -1 : activity.lastChapterIndex();
        int reached = Math.max(fromProgress, last);
        return reached >= 0 ? reached : null;
    }

    private void requireTeacher(String userId, String termId) {
        if (userId == null || userId.isBlank() || !userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account sign-in required.");
        }
        if (!authorizationService.canManageTerm(userId, termId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher access required.");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record QuizAttemptSummary(Integer attemptsUsed, Integer attemptsAllowed, Boolean passed) {
        static QuizAttemptSummary empty() {
            return new QuizAttemptSummary(null, null, null);
        }
    }

    public record StudentOverviewResponse(
            String termId,
            StudentIdentity student,
            List<AssignmentOverview> currentAssignments,
            List<AssignmentOverview> completedAssignments,
            List<BookProgress> progressByBook,
            List<QuizOverview> quizzesForBook,
            TimeInReaderSummary timeInReader,
            String ferpaNote
    ) {
    }

    public record StudentIdentity(
            String userId,
            String email,
            String displayNameOverride,
            String joinedDate
    ) {
    }

    public record AssignmentOverview(
            String assignmentId,
            String title,
            String bookId,
            String bookTitle,
            List<ClassroomContextResponse.AssignmentChapterRef> chapters,
            String chapterId,
            Integer chapterIndex,
            String chapterTitle,
            String dueDate,
            String statusLabel,
            boolean opened,
            String firstOpenedAt,
            boolean readingComplete,
            boolean readingStarted,
            boolean quizRequired,
            String quizStatus,
            Boolean quizPassed,
            Integer quizAttemptsUsed,
            Integer quizAttemptsAllowed,
            boolean characterChatRequired,
            boolean characterChatStarted
    ) {
    }

    public record BookProgress(
            String bookId,
            String bookTitle,
            int chapterCount,
            Integer reachedChapterIndex,
            String chapterLabel,
            int percentComplete,
            boolean completed,
            String lastReadAt,
            Long approximateTimeInReaderMs
    ) {
    }

    public record QuizOverview(
            String bookId,
            String bookTitle,
            String chapterId,
            String chapterTitle,
            String assignmentId,
            String assignmentTitle,
            boolean complete,
            String quizStatus,
            Boolean passed,
            int attemptsUsed,
            Integer attemptsAllowed,
            int retryAttemptsUsed,
            int bestScorePercent,
            int bestCorrectAnswers,
            Integer totalQuestions,
            String latestAttemptAt
    ) {
    }

    public record TimeInReaderSummary(
            String label,
            String caveat,
            long approximateTotalMs,
            List<TimeInBook> byBook
    ) {
    }

    public record TimeInBook(String bookId, String bookTitle, long approximateMs) {
    }
}
