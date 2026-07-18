package com.classicchatreader.service;

import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.ClassFeatureSettingsEntity;
import com.classicchatreader.entity.ClassRoleMembershipEntity;
import com.classicchatreader.entity.ClassSectionEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ClassFeatureSettingsRepository;
import com.classicchatreader.repository.ClassRoleMembershipRepository;
import com.classicchatreader.repository.ClassSectionRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.TermRepository;
import com.classicchatreader.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ClassroomAdminService {

    private final ClassSectionRepository classSectionRepository;
    private final TermRepository termRepository;
    private final ClassRoleMembershipRepository classRoleMembershipRepository;
    private final ClassFeatureSettingsRepository classFeatureSettingsRepository;
    private final AssignmentRepository assignmentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final InviteLinkService inviteLinkService;
    private final ClassroomAuthorizationService authorizationService;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final UserRepository userRepository;
    private final ClassroomProperties classroomProperties;
    private final ClassroomTeacherCapabilityService teacherCapabilityService;

    public ClassroomAdminService(
            ClassSectionRepository classSectionRepository,
            TermRepository termRepository,
            ClassRoleMembershipRepository classRoleMembershipRepository,
            ClassFeatureSettingsRepository classFeatureSettingsRepository,
            AssignmentRepository assignmentRepository,
            EnrollmentRepository enrollmentRepository,
            InviteLinkService inviteLinkService,
            ClassroomAuthorizationService authorizationService,
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            UserRepository userRepository,
            ClassroomProperties classroomProperties,
            ClassroomTeacherCapabilityService teacherCapabilityService) {
        this.classSectionRepository = classSectionRepository;
        this.termRepository = termRepository;
        this.classRoleMembershipRepository = classRoleMembershipRepository;
        this.classFeatureSettingsRepository = classFeatureSettingsRepository;
        this.assignmentRepository = assignmentRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.inviteLinkService = inviteLinkService;
        this.authorizationService = authorizationService;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.userRepository = userRepository;
        this.classroomProperties = classroomProperties;
        this.teacherCapabilityService = teacherCapabilityService;
    }

    @Transactional
    public CreateClassResult createClass(String ownerUserId, CreateClassRequest request) {
        requireUser(ownerUserId);
        teacherCapabilityService.requireCanCreateClass(ownerUserId);
        if (request == null || isBlank(request.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Class name is required.");
        }

        ClassSectionEntity section = new ClassSectionEntity();
        section.setOwnerUserId(ownerUserId);
        section.setName(request.name().trim());
        section.setCode(trimToNull(request.code()));
        section.setStatus("ACTIVE");
        classSectionRepository.save(section);

        TermEntity term = new TermEntity();
        term.setClassSectionId(section.getId());
        term.setName(isBlank(request.termName()) ? "Current term" : request.termName().trim());
        term.setStartDate(request.startDate());
        term.setEndDate(request.endDate());
        term.setStatus("ACTIVE");
        termRepository.save(term);

        ClassRoleMembershipEntity membership = new ClassRoleMembershipEntity();
        membership.setTermId(term.getId());
        membership.setUserId(ownerUserId);
        membership.setRole(ClassroomAuthorizationService.ROLE_TEACHER);
        membership.setStatus(ClassroomAuthorizationService.STATUS_ACTIVE);
        classRoleMembershipRepository.save(membership);

        ClassFeatureSettingsEntity features = defaultFeatures(term.getId(), ownerUserId, request.features());
        classFeatureSettingsRepository.save(features);

        InviteLinkService.IssuedInvite invite = inviteLinkService.issue(
                term.getId(),
                ownerUserId,
                "Default invite",
                null,
                null
        );

        return new CreateClassResult(
                section.getId(),
                term.getId(),
                section.getName(),
                term.getName(),
                invite.inviteLinkId(),
                invite.code(),
                invite.codeHint()
        );
    }

    @Transactional(readOnly = true)
    public List<ClassSummary> listOwnedClasses(String ownerUserId) {
        requireUser(ownerUserId);
        return classSectionRepository.findByOwnerUserIdAndDeletedAtIsNull(ownerUserId).stream()
                .map(section -> {
                    // Prefer most recently created ACTIVE term if multiple exist (schema allows until transition enforces).
                    Optional<TermEntity> active = termRepository
                            .findByClassSectionIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                                    section.getId(), "ACTIVE")
                            .stream()
                            .findFirst();
                    return new ClassSummary(
                            section.getId(),
                            section.getName(),
                            section.getCode(),
                            active.map(TermEntity::getId).orElse(null),
                            active.map(TermEntity::getName).orElse(null)
                    );
                })
                .toList();
    }

    @Transactional
    public InviteLinkService.IssuedInvite createInvite(String userId, String termId, String label) {
        requireTeacher(userId, termId);
        return inviteLinkService.issue(termId, userId, label, null, null);
    }

    @Transactional
    public ClassFeatureSettingsEntity updateFeatures(String userId, String termId, FeatureUpdateRequest request) {
        requireTeacher(userId, termId);
        ClassFeatureSettingsEntity features = classFeatureSettingsRepository.findById(termId)
                .orElseGet(() -> defaultFeatures(termId, userId, null));
        if (request != null) {
            if (request.quizEnabled() != null) features.setQuizEnabled(request.quizEnabled());
            if (request.recapEnabled() != null) features.setRecapEnabled(request.recapEnabled());
            if (request.ttsEnabled() != null) features.setTtsEnabled(request.ttsEnabled());
            if (request.illustrationEnabled() != null) features.setIllustrationEnabled(request.illustrationEnabled());
            if (request.characterEnabled() != null) features.setCharacterEnabled(request.characterEnabled());
            if (request.chatEnabled() != null) features.setChatEnabled(request.chatEnabled());
            if (request.speedReadingEnabled() != null) features.setSpeedReadingEnabled(request.speedReadingEnabled());
            if (request.readingBuddyEnabled() != null) features.setReadingBuddyEnabled(request.readingBuddyEnabled());
        }
        features.setUpdatedByUserId(userId);
        return classFeatureSettingsRepository.save(features);
    }

    @Transactional(readOnly = true)
    public ClassFeatureSettingsEntity getFeatures(String userId, String termId) {
        if (!authorizationService.canViewTermContext(userId, termId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this term.");
        }
        return classFeatureSettingsRepository.findById(termId)
                .orElseGet(() -> defaultFeatures(termId, null, null));
    }

    @Transactional
    public AssignmentEntity createAssignment(String userId, String termId, AssignmentWriteRequest request) {
        requireTeacher(userId, termId);
        validateAssignmentForCreate(request);
        validateCharacterChatRequirement(
                termId,
                Boolean.TRUE.equals(request != null ? request.characterChatRequired() : null));
        AssignmentEntity assignment = new AssignmentEntity();
        applyAssignmentCreate(assignment, termId, userId, request);
        if (isBlank(assignment.getStatus())) {
            assignment.setStatus("DRAFT");
        }
        return assignmentRepository.save(assignment);
    }

    @Transactional
    public AssignmentEntity updateAssignment(String userId, String assignmentId, AssignmentWriteRequest request) {
        if (userId == null || userId.isBlank() || !userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account sign-in required.");
        }
        // Always 404 for missing or unauthorized so assignment UUID existence is not probeable (no 403).
        AssignmentEntity assignment = assignmentRepository.findByIdAndDeletedAtIsNull(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found."));
        if (!authorizationService.canManageTerm(userId, assignment.getTermId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found.");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required.");
        }
        validateAssignmentForUpdate(request, assignment);
        boolean effectiveCharacterChatRequired = request.characterChatRequired() != null
                ? request.characterChatRequired()
                : assignment.isCharacterChatRequired();
        validateCharacterChatRequirement(assignment.getTermId(), effectiveCharacterChatRequired);
        applyAssignmentUpdate(assignment, userId, request);
        return assignmentRepository.save(assignment);
    }

    @Transactional(readOnly = true)
    public List<AssignmentEntity> listAssignments(String userId, String termId, boolean publishedOnly) {
        if (!authorizationService.canViewTermContext(userId, termId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this term.");
        }
        // Students may only see PUBLISHED work; teachers may list drafts when publishedOnly=false.
        boolean teacher = authorizationService.canManageTerm(userId, termId);
        boolean forcePublishedOnly = !teacher || publishedOnly;
        List<AssignmentEntity> rows;
        if (forcePublishedOnly) {
            rows = assignmentRepository
                    .findByTermIdAndStatusAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(termId, "PUBLISHED");
        } else {
            rows = assignmentRepository.findByTermIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(termId);
        }
        if (teacher) {
            return rows;
        }
        // Students: honor available_from_date as inclusive calendar open day (server calendar zone).
        LocalDate today = classroomProperties.today();
        return rows.stream()
                .filter(a -> a.getAvailableFromDate() == null || !a.getAvailableFromDate().isAfter(today))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EnrollmentRow> listRoster(String userId, String termId) {
        requireTeacher(userId, termId);
        var enrollments = enrollmentRepository
                .findByTermIdAndStatusAndDeletedAtIsNullOrderByJoinedDateAsc(termId, "ACTIVE");
        Map<String, String> emailsByUserId = userRepository.findAllById(
                        enrollments.stream().map(e -> e.getUserId()).toList())
                .stream()
                .collect(Collectors.toMap(
                        user -> user.getId(),
                        user -> user.getEmail(),
                        (first, ignored) -> first));
        return enrollments
                .stream()
                .map(e -> new EnrollmentRow(
                        e.getId(),
                        e.getUserId(),
                        emailsByUserId.get(e.getUserId()),
                        e.getStatus(),
                        e.getJoinedDate(),
                        e.getDisplayNameOverride()
                ))
                .toList();
    }

    private void applyAssignmentCreate(
            AssignmentEntity assignment, String termId, String userId, AssignmentWriteRequest request) {
        assignment.setTermId(termId);
        assignment.setTitle(request.title().trim());
        assignment.setBookId(request.bookId().trim());
        assignment.setChapterId(trimToNull(request.chapterId()));
        assignment.setChapterIndex(request.chapterIndex());
        assignment.setDueDate(request.dueDate());
        assignment.setAvailableFromDate(request.availableFromDate());
        assignment.setQuizRequired(Boolean.TRUE.equals(request.quizRequired()));
        assignment.setCharacterChatRequired(Boolean.TRUE.equals(request.characterChatRequired()));
        if (request.sortOrder() != null) {
            assignment.setSortOrder(request.sortOrder());
        }
        if (!isBlank(request.status())) {
            assignment.setStatus(request.status().trim().toUpperCase());
        }
        assignment.setCreatedByUserId(userId);
    }

    /** Partial update: only non-null / non-blank request fields change existing row. */
    private void applyAssignmentUpdate(AssignmentEntity assignment, String userId, AssignmentWriteRequest request) {
        if (!isBlank(request.title())) {
            assignment.setTitle(request.title().trim());
        }
        if (!isBlank(request.bookId())) {
            assignment.setBookId(request.bookId().trim());
        }
        if (request.chapterId() != null) {
            // Explicit empty string clears chapter link.
            String chapterId = trimToNull(request.chapterId());
            assignment.setChapterId(chapterId);
            if (chapterId == null) {
                assignment.setChapterIndex(null);
            }
        }
        if (request.chapterIndex() != null) {
            assignment.setChapterIndex(request.chapterIndex());
        }
        if (Boolean.TRUE.equals(request.clearDueDate())) {
            assignment.setDueDate(null);
        } else if (request.dueDate() != null) {
            assignment.setDueDate(request.dueDate());
        }
        if (Boolean.TRUE.equals(request.clearAvailableFromDate())) {
            assignment.setAvailableFromDate(null);
        } else if (request.availableFromDate() != null) {
            assignment.setAvailableFromDate(request.availableFromDate());
        }
        if (request.quizRequired() != null) {
            assignment.setQuizRequired(request.quizRequired());
        }
        if (request.characterChatRequired() != null) {
            assignment.setCharacterChatRequired(request.characterChatRequired());
        }
        if (request.sortOrder() != null) {
            assignment.setSortOrder(request.sortOrder());
        }
        if (!isBlank(request.status())) {
            assignment.setStatus(request.status().trim().toUpperCase());
        }
        if (assignment.getCreatedByUserId() == null) {
            assignment.setCreatedByUserId(userId);
        }
    }

    private void validateAssignmentForCreate(AssignmentWriteRequest request) {
        if (request == null || isBlank(request.title()) || isBlank(request.bookId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title and bookId are required.");
        }
        validateBookAndChapter(
                request.bookId().trim(),
                trimToNull(request.chapterId()),
                request.chapterIndex());
        validateAssignmentStatus(request.status());
    }

    private void validateAssignmentForUpdate(AssignmentWriteRequest request, AssignmentEntity existing) {
        String effectiveBookId = !isBlank(request.bookId()) ? request.bookId().trim() : existing.getBookId();
        String effectiveChapterId = request.chapterId() != null
                ? trimToNull(request.chapterId())
                : existing.getChapterId();
        Integer effectiveChapterIndex;
        if (request.chapterIndex() != null) {
            effectiveChapterIndex = request.chapterIndex();
        } else if (request.chapterId() != null && trimToNull(request.chapterId()) == null) {
            // Explicit empty chapterId means whole-book; do not validate the previous index.
            effectiveChapterIndex = null;
        } else {
            effectiveChapterIndex = existing.getChapterIndex();
        }
        boolean chapterTargetChanged = request.chapterId() != null
                || request.chapterIndex() != null
                || !isBlank(request.bookId());
        if (!isBlank(request.bookId()) && !bookRepository.existsById(effectiveBookId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown bookId.");
        }
        if (chapterTargetChanged) {
            validateBookAndChapter(effectiveBookId, effectiveChapterId, effectiveChapterIndex);
        }
        validateAssignmentStatus(request.status());
    }

    private void validateBookAndChapter(String bookId, String chapterId, Integer chapterIndex) {
        if (!bookRepository.existsById(bookId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown bookId.");
        }
        if (chapterId != null) {
            Optional<ChapterEntity> chapter = chapterRepository.findByIdWithBook(chapterId);
            if (chapter.isEmpty() || chapter.get().getBook() == null
                    || !Objects.equals(bookId, chapter.get().getBook().getId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "chapterId must belong to the given bookId.");
            }
            return;
        }
        if (chapterIndex != null) {
            int index = Math.max(0, chapterIndex);
            if (chapterRepository.findByBookIdAndChapterIndex(bookId, index).isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "chapterIndex does not exist for the given bookId.");
            }
        }
    }

    private void validateAssignmentStatus(String statusRaw) {
        if (!isBlank(statusRaw)) {
            String status = statusRaw.trim().toUpperCase();
            if (!status.equals("DRAFT") && !status.equals("PUBLISHED") && !status.equals("ARCHIVED")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid assignment status.");
            }
        }
    }

    private void validateCharacterChatRequirement(String termId, boolean characterChatRequired) {
        if (!characterChatRequired) {
            return;
        }
        ClassFeatureSettingsEntity features = classFeatureSettingsRepository.findById(termId)
                .orElseGet(() -> defaultFeatures(termId, null, null));
        if (!features.isCharacterEnabled() || !features.isChatEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Character chat cannot be required while character or chat features are disabled for this class."
            );
        }
    }

    private ClassFeatureSettingsEntity defaultFeatures(String termId, String userId, FeatureUpdateRequest override) {
        ClassFeatureSettingsEntity features = new ClassFeatureSettingsEntity();
        features.setTermId(termId);
        features.setUpdatedByUserId(userId);
        if (override != null) {
            if (override.quizEnabled() != null) features.setQuizEnabled(override.quizEnabled());
            if (override.recapEnabled() != null) features.setRecapEnabled(override.recapEnabled());
            if (override.ttsEnabled() != null) features.setTtsEnabled(override.ttsEnabled());
            if (override.illustrationEnabled() != null) features.setIllustrationEnabled(override.illustrationEnabled());
            if (override.characterEnabled() != null) features.setCharacterEnabled(override.characterEnabled());
            if (override.chatEnabled() != null) features.setChatEnabled(override.chatEnabled());
            if (override.speedReadingEnabled() != null) features.setSpeedReadingEnabled(override.speedReadingEnabled());
            if (override.readingBuddyEnabled() != null) features.setReadingBuddyEnabled(override.readingBuddyEnabled());
        }
        return features;
    }

    private void requireTeacher(String userId, String termId) {
        requireUser(userId);
        if (!authorizationService.canManageTerm(userId, termId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher access required.");
        }
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account sign-in required.");
        }
        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account sign-in required.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record CreateClassRequest(
            String name,
            String code,
            String termName,
            LocalDate startDate,
            LocalDate endDate,
            FeatureUpdateRequest features
    ) {
    }

    public record FeatureUpdateRequest(
            Boolean quizEnabled,
            Boolean recapEnabled,
            Boolean ttsEnabled,
            Boolean illustrationEnabled,
            Boolean characterEnabled,
            Boolean chatEnabled,
            Boolean speedReadingEnabled,
            Boolean readingBuddyEnabled
    ) {
    }

    public record AssignmentWriteRequest(
            String title,
            String bookId,
            String chapterId,
            Integer chapterIndex,
            LocalDate dueDate,
            LocalDate availableFromDate,
            Boolean quizRequired,
            Boolean characterChatRequired,
            Integer sortOrder,
            String status,
            /** When true on update, clears dueDate even if dueDate is null. */
            Boolean clearDueDate,
            /** When true on update, clears availableFromDate even if availableFromDate is null. */
            Boolean clearAvailableFromDate
    ) {
        public AssignmentWriteRequest(
                String title,
                String bookId,
                String chapterId,
                Integer chapterIndex,
                LocalDate dueDate,
                LocalDate availableFromDate,
                Boolean quizRequired,
                Integer sortOrder,
                String status) {
            this(title, bookId, chapterId, chapterIndex, dueDate, availableFromDate,
                    quizRequired, null, sortOrder, status, null, null);
        }

        public AssignmentWriteRequest(
                String title,
                String bookId,
                String chapterId,
                Integer chapterIndex,
                LocalDate dueDate,
                LocalDate availableFromDate,
                Boolean quizRequired,
                Boolean characterChatRequired,
                Integer sortOrder,
                String status) {
            this(title, bookId, chapterId, chapterIndex, dueDate, availableFromDate,
                    quizRequired, characterChatRequired, sortOrder, status, null, null);
        }
    }

    public record CreateClassResult(
            String classId,
            String termId,
            String className,
            String termName,
            String inviteLinkId,
            String inviteCode,
            String inviteCodeHint
    ) {
    }

    public record ClassSummary(
            String classId,
            String className,
            String code,
            String activeTermId,
            String activeTermName
    ) {
    }

    public record EnrollmentRow(
            String enrollmentId,
            String userId,
            String email,
            String status,
            LocalDate joinedDate,
            String displayNameOverride
    ) {
    }
}
