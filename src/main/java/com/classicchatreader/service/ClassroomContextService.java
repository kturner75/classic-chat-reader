package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomDemoProperties;
import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ClassFeatureSettingsEntity;
import com.classicchatreader.entity.ClassRoleMembershipEntity;
import com.classicchatreader.entity.ClassSectionEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.entity.UserEntity;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.model.ClassroomContextResponse.ClassAssignment;
import com.classicchatreader.model.ClassroomContextResponse.ClassroomFeatureStates;
import com.classicchatreader.model.ClassroomContextResponse.QuizRequirementStatus;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ClassFeatureSettingsRepository;
import com.classicchatreader.repository.ClassRoleMembershipRepository;
import com.classicchatreader.repository.ClassSectionRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.TermRepository;
import com.classicchatreader.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class ClassroomContextService {

    private static final String DEFAULT_CLASS_ID = "demo-class";
    private static final String DEFAULT_CLASS_NAME = "Assigned Reading";

    private final ClassroomDemoProperties classroomDemoProperties;
    private final ClassroomProperties classroomProperties;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRoleMembershipRepository classRoleMembershipRepository;
    private final TermRepository termRepository;
    private final ClassSectionRepository classSectionRepository;
    private final ClassFeatureSettingsRepository classFeatureSettingsRepository;
    private final AssignmentRepository assignmentRepository;
    private final UserRepository userRepository;

    public ClassroomContextService(
            ClassroomDemoProperties classroomDemoProperties,
            ClassroomProperties classroomProperties,
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            QuizAttemptRepository quizAttemptRepository,
            EnrollmentRepository enrollmentRepository,
            ClassRoleMembershipRepository classRoleMembershipRepository,
            TermRepository termRepository,
            ClassSectionRepository classSectionRepository,
            ClassFeatureSettingsRepository classFeatureSettingsRepository,
            AssignmentRepository assignmentRepository,
            UserRepository userRepository) {
        this.classroomDemoProperties = classroomDemoProperties;
        this.classroomProperties = classroomProperties;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.classRoleMembershipRepository = classRoleMembershipRepository;
        this.termRepository = termRepository;
        this.classSectionRepository = classSectionRepository;
        this.classFeatureSettingsRepository = classFeatureSettingsRepository;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
    }

    /**
     * Resolve classroom context for an optional authenticated account.
     * DB membership (student enrollment or teacher role) wins when mode allows;
     * demo properties are a fallback when mode allows and no DB membership exists.
     */
    public ClassroomContextResponse getContext(String userId) {
        return getContext(userId, null);
    }

    /**
     * @param preferredTermId optional preferred term when the user has multiple memberships
     */
    public ClassroomContextResponse getContext(String userId, String preferredTermId) {
        if (classroomProperties.allowsDatabase() && userId != null && !userId.isBlank()) {
            Optional<MembershipCandidate> candidate = selectMembership(userId, preferredTermId);
            if (candidate.isPresent()) {
                return buildDbContext(userId, candidate.get());
            }
            // Explicit term preferred but not a membership → not enrolled for that term (no silent fallback).
            if (preferredTermId != null && !preferredTermId.isBlank()) {
                return ClassroomContextResponse.notEnrolled();
            }
            if (classroomProperties.isDatabaseMode()) {
                return ClassroomContextResponse.notEnrolled();
            }
        }

        if (classroomProperties.allowsDemoFallback() && classroomDemoProperties.isEnabled()) {
            return buildDemoContext(userId);
        }

        return ClassroomContextResponse.notEnrolled();
    }

    public ClassroomContextResponse getContext() {
        return getContext(null, null);
    }

    private Optional<MembershipCandidate> selectMembership(String userId, String preferredTermId) {
        List<MembershipCandidate> candidates = new ArrayList<>();

        for (EnrollmentEntity enrollment :
                enrollmentRepository.findByUserIdAndStatusAndDeletedAtIsNull(userId, "ACTIVE")) {
            termRepository.findByIdAndDeletedAtIsNull(enrollment.getTermId())
                    .filter(t -> "ACTIVE".equals(t.getStatus()))
                    .filter(this::hasLiveSection)
                    .ifPresent(term -> candidates.add(new MembershipCandidate(
                            term,
                            ClassroomAuthorizationService.ROLE_STUDENT,
                            enrollment.getJoinedDate() != null
                                    ? enrollment.getJoinedDate().atStartOfDay(ZoneOffset.UTC).toLocalDateTime()
                                    : enrollment.getCreatedAt(),
                            enrollment.getCreatedAt()
                    )));
        }

        for (ClassRoleMembershipEntity membership :
                classRoleMembershipRepository.findByUserIdAndStatus(userId, "ACTIVE")) {
            if (!ClassroomAuthorizationService.isTeacherLikeRole(membership.getRole())) {
                continue;
            }
            termRepository.findByIdAndDeletedAtIsNull(membership.getTermId())
                    .filter(t -> "ACTIVE".equals(t.getStatus()))
                    .filter(this::hasLiveSection)
                    .ifPresent(term -> candidates.add(new MembershipCandidate(
                            term,
                            ClassroomAuthorizationService.ROLE_TEACHER,
                            membership.getCreatedAt(),
                            membership.getCreatedAt()
                    )));
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // One candidate per term: prefer teacher membership (with its activity timestamps) over student enrollment.
        Map<String, MembershipCandidate> byTerm = new HashMap<>();
        for (MembershipCandidate c : candidates) {
            String termId = c.term().getId();
            MembershipCandidate existing = byTerm.get(termId);
            if (existing == null) {
                byTerm.put(termId, c);
                continue;
            }
            boolean candidateTeacher = ClassroomAuthorizationService.ROLE_TEACHER.equals(c.role());
            boolean existingTeacher = ClassroomAuthorizationService.ROLE_TEACHER.equals(existing.role());
            if (candidateTeacher && !existingTeacher) {
                byTerm.put(termId, c);
            } else if (candidateTeacher == existingTeacher
                    && c.activityAt() != null
                    && (existing.activityAt() == null || c.activityAt().isAfter(existing.activityAt()))) {
                byTerm.put(termId, c);
            }
        }
        List<MembershipCandidate> collapsed = new ArrayList<>(byTerm.values());

        if (preferredTermId != null && !preferredTermId.isBlank()) {
            return collapsed.stream()
                    .filter(c -> preferredTermId.equals(c.term().getId()))
                    .findFirst();
        }

        if (collapsed.size() == 1) {
            return Optional.of(collapsed.get(0));
        }

        return collapsed.stream()
                .max(Comparator
                        .comparing(MembershipCandidate::activityAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(c -> c.term().getId()));
    }

    /** Membership only counts when the parent class section is live (not soft-deleted, status ACTIVE). */
    private boolean hasLiveSection(TermEntity term) {
        return classSectionRepository.findByIdAndDeletedAtIsNull(term.getClassSectionId())
                .filter(s -> "ACTIVE".equals(s.getStatus()))
                .isPresent();
    }

    private ClassroomContextResponse buildDbContext(String userId, MembershipCandidate candidate) {
        TermEntity term = candidate.term();
        // Section was verified live during candidate selection; re-check for race/soft-delete/archive.
        ClassSectionEntity section = classSectionRepository.findByIdAndDeletedAtIsNull(term.getClassSectionId())
                .filter(s -> "ACTIVE".equals(s.getStatus()))
                .orElse(null);
        if (section == null) {
            return ClassroomContextResponse.notEnrolled();
        }

        String teacherName = resolveTeacherName(term.getId(), section.getOwnerUserId());
        ClassroomFeatureStates features = resolveDbFeatures(term.getId());
        boolean studentView = ClassroomAuthorizationService.ROLE_STUDENT.equals(candidate.role());
        List<ClassAssignment> assignments = buildDbAssignments(term.getId(), userId, studentView);

        return new ClassroomContextResponse(
                true,
                section.getId(),
                section.getName(),
                teacherName,
                features,
                assignments,
                term.getId(),
                candidate.role()
        );
    }

    private String resolveTeacherName(String termId, String ownerUserId) {
        List<ClassRoleMembershipEntity> teachers =
                classRoleMembershipRepository.findByTermIdAndStatus(termId, "ACTIVE");
        String teacherUserId = teachers.stream()
                .filter(m -> ClassroomAuthorizationService.ROLE_TEACHER.equals(m.getRole()))
                .map(ClassRoleMembershipEntity::getUserId)
                .findFirst()
                .orElse(ownerUserId);
        if (teacherUserId == null) {
            return null;
        }
        return userRepository.findById(teacherUserId)
                .map(UserEntity::getEmail)
                .orElse(null);
    }

    private ClassroomFeatureStates resolveDbFeatures(String termId) {
        Optional<ClassFeatureSettingsEntity> settings = classFeatureSettingsRepository.findById(termId);
        if (settings.isEmpty()) {
            return ClassroomFeatureStates.defaults();
        }
        ClassFeatureSettingsEntity f = settings.get();
        return new ClassroomFeatureStates(
                f.isQuizEnabled(),
                f.isRecapEnabled(),
                f.isTtsEnabled(),
                f.isIllustrationEnabled(),
                f.isCharacterEnabled(),
                f.isChatEnabled(),
                f.isSpeedReadingEnabled(),
                f.isReadingBuddyEnabled()
        );
    }

    private List<ClassAssignment> buildDbAssignments(String termId, String userId, boolean studentView) {
        List<AssignmentEntity> rows = assignmentRepository
                .findByTermIdAndStatusAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(termId, "PUBLISHED");
        LocalDate today = classroomProperties.today();
        List<ClassAssignment> resolved = new ArrayList<>();
        for (AssignmentEntity row : rows) {
            if (studentView
                    && row.getAvailableFromDate() != null
                    && row.getAvailableFromDate().isAfter(today)) {
                continue;
            }
            ClassAssignment assignment = resolveDbAssignment(row, userId, studentView);
            if (assignment != null) {
                resolved.add(assignment);
            }
        }
        return resolved;
    }

    private ClassAssignment resolveDbAssignment(AssignmentEntity row, String userId, boolean studentView) {
        String bookId = row.getBookId();
        Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
        Optional<ChapterEntity> chapterOpt = resolveChapter(row.getChapterId(), row.getChapterIndex(), bookId);

        String chapterId = chapterOpt.map(ChapterEntity::getId).orElse(row.getChapterId());
        Integer chapterIndex = chapterOpt.map(ChapterEntity::getChapterIndex).orElse(row.getChapterIndex());
        String chapterTitle = chapterOpt.map(ChapterEntity::getTitle).orElseGet(() -> {
            if (chapterIndex == null) {
                return null;
            }
            return "Chapter " + Math.max(1, chapterIndex + 1);
        });

        QuizAttemptSummary attemptSummary = studentView
                ? resolveQuizAttemptSummary(row, chapterId, userId)
                : QuizAttemptSummary.empty();
        QuizRequirementStatus quizStatus;
        if (!studentView) {
            // Teachers see requirement presence without personal completion chips
            quizStatus = row.isQuizRequired() ? QuizRequirementStatus.UNKNOWN : QuizRequirementStatus.NOT_REQUIRED;
        } else {
            quizStatus = resolveQuizStatus(row, chapterId, userId, attemptSummary);
        }

        // Calendar DATE only (assignments.due_date is SQL DATE / LocalDate). FE treats date-only as inclusive local due day.
        String dueAt = formatDueDate(row.getDueDate());

        return new ClassAssignment(
                row.getId(),
                row.getTitle(),
                bookId,
                bookOpt.map(BookEntity::getTitle).orElse("Book unavailable"),
                bookOpt.map(BookEntity::getAuthor).orElse(""),
                chapterId,
                chapterIndex,
                chapterTitle,
                dueAt,
                row.isQuizRequired(),
                quizStatus,
                row.isCharacterChatRequired(),
                bookOpt.isPresent(),
                row.getQuizPassMinCorrect(),
                row.getQuizMaxRetries(),
                attemptSummary.attemptsUsed(),
                attemptSummary.attemptsAllowed(),
                attemptSummary.passed()
        );
    }

    /** SQL DATE / LocalDate → ISO calendar day {@code YYYY-MM-DD} (not a timestamp). */
    static String formatDueDate(LocalDate dueDate) {
        return dueDate == null ? null : dueDate.toString();
    }

    private ClassroomContextResponse buildDemoContext(String userId) {
        List<ClassAssignment> assignments = buildDemoAssignments(userId);
        ClassroomFeatureStates features = resolveDemoFeatureStates();

        return new ClassroomContextResponse(
                true,
                normalizeOrDefault(classroomDemoProperties.getClassId(), DEFAULT_CLASS_ID),
                normalizeOrDefault(classroomDemoProperties.getClassName(), DEFAULT_CLASS_NAME),
                normalizeOrNull(classroomDemoProperties.getTeacherName()),
                features,
                assignments,
                null,
                null
        );
    }

    private List<ClassAssignment> buildDemoAssignments(String userId) {
        List<ClassroomDemoProperties.Assignment> configured = classroomDemoProperties.getAssignments();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }

        List<ClassAssignment> resolved = new ArrayList<>();
        for (int i = 0; i < configured.size(); i++) {
            ClassAssignment assignment = resolveDemoAssignment(configured.get(i), i, userId);
            if (assignment != null) {
                resolved.add(assignment);
            }
        }
        return resolved;
    }

    private ClassAssignment resolveDemoAssignment(
            ClassroomDemoProperties.Assignment configured, int index, String userId) {
        if (configured == null) {
            return null;
        }

        String bookId = normalizeOrNull(configured.getBookId());
        if (bookId == null) {
            return null;
        }

        Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
        Optional<ChapterEntity> chapterOpt = resolveChapter(
                configured.getChapterId(), configured.getChapterIndex(), bookId);

        String assignmentId = normalizeOrDefault(
                configured.getAssignmentId(),
                "assignment-" + (index + 1)
        );
        String title = normalizeOrDefault(configured.getTitle(), "Assigned Reading");
        String bookTitle = bookOpt.map(BookEntity::getTitle).orElse("Book unavailable");
        String bookAuthor = bookOpt.map(BookEntity::getAuthor).orElse("");
        String chapterId = chapterOpt.map(ChapterEntity::getId).orElse(null);
        Integer chapterIndex = chapterOpt.map(ChapterEntity::getChapterIndex).orElse(configured.getChapterIndex());
        String chapterTitle = chapterOpt.map(ChapterEntity::getTitle).orElseGet(() -> {
            Integer configuredIndex = configured.getChapterIndex();
            if (configuredIndex == null) {
                return null;
            }
            return "Chapter " + Math.max(1, configuredIndex + 1);
        });
        String dueAt = normalizeOrNull(configured.getDueAt());
        boolean quizRequired = configured.isQuizRequired();
        QuizRequirementStatus quizStatus = resolveQuizStatus(quizRequired, chapterId, userId);

        return new ClassAssignment(
                assignmentId,
                title,
                bookId,
                bookTitle,
                bookAuthor,
                chapterId,
                chapterIndex,
                chapterTitle,
                dueAt,
                quizRequired,
                quizStatus,
                false,
                bookOpt.isPresent(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private Optional<ChapterEntity> resolveChapter(String chapterIdRaw, Integer chapterIndex, String bookId) {
        String chapterId = normalizeOrNull(chapterIdRaw);
        if (chapterId != null) {
            return chapterRepository.findByIdWithBook(chapterId)
                    .filter(chapter -> Objects.equals(chapter.getBook().getId(), bookId));
        }

        if (chapterIndex == null) {
            return Optional.empty();
        }

        return chapterRepository.findByBookIdAndChapterIndex(bookId, Math.max(0, chapterIndex));
    }

    private QuizRequirementStatus resolveQuizStatus(boolean quizRequired, String chapterId, String userId) {
        if (!quizRequired) {
            return QuizRequirementStatus.NOT_REQUIRED;
        }
        if (chapterId == null || chapterId.isBlank()) {
            return QuizRequirementStatus.UNKNOWN;
        }
        // Demo / legacy path without pass rules: any attempt completes.
        if (userId != null && !userId.isBlank()) {
            return quizAttemptRepository.existsByChapterIdAndUserId(chapterId, userId)
                    ? QuizRequirementStatus.COMPLETE
                    : QuizRequirementStatus.PENDING;
        }
        return quizAttemptRepository.existsByChapterId(chapterId)
                ? QuizRequirementStatus.COMPLETE
                : QuizRequirementStatus.PENDING;
    }

    private QuizRequirementStatus resolveQuizStatus(
            AssignmentEntity row,
            String chapterId,
            String userId,
            QuizAttemptSummary attemptSummary) {
        if (!row.isQuizRequired()) {
            return QuizRequirementStatus.NOT_REQUIRED;
        }
        if (chapterId == null || chapterId.isBlank()) {
            return QuizRequirementStatus.UNKNOWN;
        }
        if (row.getQuizPassMinCorrect() == null) {
            return resolveQuizStatus(true, chapterId, userId);
        }
        if (Boolean.TRUE.equals(attemptSummary.passed())) {
            return QuizRequirementStatus.COMPLETE;
        }
        return QuizRequirementStatus.PENDING;
    }

    private QuizAttemptSummary resolveQuizAttemptSummary(
            AssignmentEntity row, String chapterId, String userId) {
        if (!row.isQuizRequired()
                || chapterId == null
                || chapterId.isBlank()
                || userId == null
                || userId.isBlank()) {
            return QuizAttemptSummary.empty();
        }
        Integer minCorrect = row.getQuizPassMinCorrect();
        Integer maxRetries = row.getQuizMaxRetries();
        java.time.LocalDateTime since = attemptWindowStart(row);
        long used = quizAttemptRepository.countByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                chapterId, userId, since);
        Integer allowed = minCorrect != null && maxRetries != null ? 1 + maxRetries : null;
        Boolean passed = null;
        if (minCorrect != null) {
            int best = quizAttemptRepository.findMaxCorrectAnswersByChapterIdAndUserIdAndCreatedAtOnOrAfter(
                    chapterId, userId, since);
            passed = best >= minCorrect;
        } else if (used > 0) {
            passed = true;
        }
        return new QuizAttemptSummary(
                used > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) used,
                allowed,
                passed);
    }

    private static java.time.LocalDateTime attemptWindowStart(AssignmentEntity assignment) {
        java.time.LocalDateTime since = assignment.getQuizRulesActivatedAt();
        if (since == null) {
            since = assignment.getCreatedAt();
        }
        if (assignment.getAvailableFromDate() != null) {
            java.time.LocalDateTime open = java.time.LocalDateTime.of(
                    assignment.getAvailableFromDate(), java.time.LocalTime.MIN);
            if (since == null || open.isAfter(since)) {
                since = open;
            }
        }
        return since != null ? since : java.time.LocalDateTime.of(1970, 1, 1, 0, 0);
    }

    private record QuizAttemptSummary(Integer attemptsUsed, Integer attemptsAllowed, Boolean passed) {
        static QuizAttemptSummary empty() {
            return new QuizAttemptSummary(null, null, null);
        }
    }

    private ClassroomFeatureStates resolveDemoFeatureStates() {
        ClassroomDemoProperties.Features configured = classroomDemoProperties.getFeatures();
        if (configured == null) {
            return ClassroomFeatureStates.defaults();
        }

        return new ClassroomFeatureStates(
                configured.isQuizEnabled(),
                configured.isRecapEnabled(),
                configured.isTtsEnabled(),
                configured.isIllustrationEnabled(),
                configured.isCharacterEnabled(),
                configured.isChatEnabled(),
                configured.isSpeedReadingEnabled(),
                configured.isReadingBuddyEnabled()
        );
    }

    private String normalizeOrNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeOrDefault(String value, String fallback) {
        String normalized = normalizeOrNull(value);
        return normalized == null ? fallback : normalized;
    }

    private record MembershipCandidate(
            TermEntity term,
            String role,
            java.time.LocalDateTime activityAt,
            java.time.LocalDateTime createdAt
    ) {
    }
}
