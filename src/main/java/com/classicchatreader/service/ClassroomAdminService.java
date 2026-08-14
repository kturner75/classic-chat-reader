package com.classicchatreader.service;

import com.classicchatreader.entity.AssignmentChapterEntity;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.ClassFeatureSettingsEntity;
import com.classicchatreader.entity.ClassRoleMembershipEntity;
import com.classicchatreader.entity.ClassSectionEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.repository.AssignmentQuizRepository;
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

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    private final AssignmentQuizRepository assignmentQuizRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final InviteLinkService inviteLinkService;
    private final ClassroomAuthorizationService authorizationService;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final UserRepository userRepository;
    private final ClassroomProperties classroomProperties;
    private final ClassroomTeacherCapabilityService teacherCapabilityService;
    private final ClassroomEffectiveQuizService classroomEffectiveQuizService;
    private final ChapterQuizService chapterQuizService;
    private final EntityManager entityManager;

    public ClassroomAdminService(
            ClassSectionRepository classSectionRepository,
            TermRepository termRepository,
            ClassRoleMembershipRepository classRoleMembershipRepository,
            ClassFeatureSettingsRepository classFeatureSettingsRepository,
            AssignmentRepository assignmentRepository,
            AssignmentQuizRepository assignmentQuizRepository,
            EnrollmentRepository enrollmentRepository,
            InviteLinkService inviteLinkService,
            ClassroomAuthorizationService authorizationService,
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            UserRepository userRepository,
            ClassroomProperties classroomProperties,
            ClassroomTeacherCapabilityService teacherCapabilityService,
            ClassroomEffectiveQuizService classroomEffectiveQuizService,
            ChapterQuizService chapterQuizService,
            EntityManager entityManager) {
        this.classSectionRepository = classSectionRepository;
        this.termRepository = termRepository;
        this.classRoleMembershipRepository = classRoleMembershipRepository;
        this.classFeatureSettingsRepository = classFeatureSettingsRepository;
        this.assignmentRepository = assignmentRepository;
        this.assignmentQuizRepository = assignmentQuizRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.inviteLinkService = inviteLinkService;
        this.authorizationService = authorizationService;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.userRepository = userRepository;
        this.classroomProperties = classroomProperties;
        this.teacherCapabilityService = teacherCapabilityService;
        this.classroomEffectiveQuizService = classroomEffectiveQuizService;
        this.chapterQuizService = chapterQuizService;
        this.entityManager = entityManager;
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
        validateFeatureUpdate(termId, request);
        if (request != null) {
            if (request.quizEnabled() != null) features.setQuizEnabled(request.quizEnabled());
            if (request.recapEnabled() != null) features.setRecapEnabled(request.recapEnabled());
            if (request.ttsEnabled() != null) features.setTtsEnabled(request.ttsEnabled());
            if (request.illustrationEnabled() != null) features.setIllustrationEnabled(request.illustrationEnabled());
            if (request.characterEnabled() != null) features.setCharacterEnabled(request.characterEnabled());
            if (request.chatEnabled() != null) features.setChatEnabled(request.chatEnabled());
            if (request.speedReadingEnabled() != null) features.setSpeedReadingEnabled(request.speedReadingEnabled());
            if (request.readingBuddyEnabled() != null) features.setReadingBuddyEnabled(request.readingBuddyEnabled());
            applyQuizDefaults(features, request);
        }
        features.setUpdatedByUserId(userId);
        return classFeatureSettingsRepository.save(features);
    }

    private void applyQuizDefaults(ClassFeatureSettingsEntity features, FeatureUpdateRequest request) {
        if (request.defaultQuizQuestionCount() != null) {
            int count = request.defaultQuizQuestionCount();
            if (count < 1 || count > 20) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "defaultQuizQuestionCount must be between 1 and 20.");
            }
            features.setDefaultQuizQuestionCount(count);
        }
        if (request.defaultQuizOptionCount() != null) {
            int options = request.defaultQuizOptionCount();
            if (options < 2 || options > 6) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "defaultQuizOptionCount must be between 2 and 6.");
            }
            features.setDefaultQuizOptionCount(options);
        }
        if (Boolean.TRUE.equals(request.clearDefaultQuizPassRules())) {
            features.setDefaultQuizPassMinCorrect(null);
            features.setDefaultQuizMaxRetries(null);
        } else {
            if (request.defaultQuizPassMinCorrect() != null) {
                int min = request.defaultQuizPassMinCorrect();
                if (min < 1) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "defaultQuizPassMinCorrect must be at least 1.");
                }
                features.setDefaultQuizPassMinCorrect(min);
            }
            if (request.defaultQuizMaxRetries() != null) {
                int retries = request.defaultQuizMaxRetries();
                if (retries < 0) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "defaultQuizMaxRetries cannot be negative.");
                }
                if (retries > 20) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "defaultQuizMaxRetries cannot exceed 20.");
                }
                features.setDefaultQuizMaxRetries(retries);
            }
        }
        Integer min = features.getDefaultQuizPassMinCorrect();
        Integer retries = features.getDefaultQuizMaxRetries();
        if (min != null && retries == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "defaultQuizMaxRetries is required when defaultQuizPassMinCorrect is set (use 0 for no retries).");
        }
        if (min == null && retries != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "defaultQuizMaxRetries requires defaultQuizPassMinCorrect.");
        }
        int questionCount = features.getDefaultQuizQuestionCount() > 0
                ? features.getDefaultQuizQuestionCount()
                : 5;
        if (min != null && min > questionCount) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "defaultQuizPassMinCorrect cannot exceed defaultQuizQuestionCount ("
                            + questionCount + ").");
        }
    }

    private void validateFeatureUpdate(String termId, FeatureUpdateRequest request) {
        if (request == null
                || (!Boolean.FALSE.equals(request.characterEnabled())
                        && !Boolean.FALSE.equals(request.chatEnabled()))) {
            return;
        }
        if (assignmentRepository.existsByTermIdAndCharacterChatRequiredTrueAndDeletedAtIsNull(termId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Characters and AI chat cannot be disabled while assignments require character chat. "
                            + "Remove the requirement from those assignments first."
            );
        }
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
        validateQuizPassRulesForCreate(termId, request);
        AssignmentEntity assignment = new AssignmentEntity();
        applyAssignmentCreate(assignment, termId, userId, request);
        if (isBlank(assignment.getStatus())) {
            assignment.setStatus("DRAFT");
        }
        AssignmentEntity saved = assignmentRepository.save(assignment);
        validatePublishedQuizRequirement(saved);
        return saved;
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
        // Lock quiz content before mutation when pass rules may be validated against quiz size,
        // then refresh so a concurrent publication cannot leave a stale activation timestamp.
        java.util.TreeSet<String> lockOrder = new java.util.TreeSet<>();
        for (AssignmentChapterEntity chapter : assignment.getChapters()) {
            if (chapter.getChapterId() != null && !chapter.getChapterId().isBlank()) {
                lockOrder.add(chapter.getChapterId());
            }
        }
        for (String chapterId : resolveRequestedChapterIds(request, assignment)) {
            lockOrder.add(chapterId);
        }
        for (String chapterToLock : lockOrder) {
            chapterQuizService.lockQuizContent(chapterToLock);
        }
        entityManager.refresh(assignment);
        validateAssignmentForUpdate(request, assignment);
        boolean effectiveCharacterChatRequired = request.characterChatRequired() != null
                ? request.characterChatRequired()
                : assignment.isCharacterChatRequired();
        validateCharacterChatRequirement(assignment.getTermId(), effectiveCharacterChatRequired);
        validateQuizPassRulesForUpdate(request, assignment);
        applyAssignmentUpdate(assignment, userId, request);
        AssignmentEntity saved = assignmentRepository.save(assignment);
        validatePublishedQuizRequirement(saved);
        return saved;
    }

    @Transactional
    public void deleteDraftAssignment(String userId, String assignmentId) {
        if (userId == null || userId.isBlank() || !userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account sign-in required.");
        }
        AssignmentEntity assignment = assignmentRepository.findByIdAndDeletedAtIsNull(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found."));
        if (!authorizationService.canManageTerm(userId, assignment.getTermId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found.");
        }
        if (!"DRAFT".equalsIgnoreCase(assignment.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Only draft assignments can be deleted.");
        }
        assignment.setDeletedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        assignmentRepository.save(assignment);
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
        replaceAssignmentChapters(assignment, request.bookId().trim(), resolveRequestedChapterIds(request, null));
        assignment.setDueDate(request.dueDate());
        assignment.setAvailableFromDate(request.availableFromDate());
        assignment.setQuizRequired(Boolean.TRUE.equals(request.quizRequired()));
        assignment.setCharacterChatRequired(Boolean.TRUE.equals(request.characterChatRequired()));
        assignment.setQuizSource(resolveQuizSource(assignment, request, true));
        applyQuizPassRulesOnCreate(assignment, request);
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
        List<String> previousChapterIds = assignment.getChapters().stream()
                .map(AssignmentChapterEntity::getChapterId)
                .toList();
        LocalDate previousAvailableFrom = assignment.getAvailableFromDate();
        if (!isBlank(request.title())) {
            assignment.setTitle(request.title().trim());
        }
        if (!isBlank(request.bookId())) {
            assignment.setBookId(request.bookId().trim());
        }
        if (requestTouchesChapters(request) || !isBlank(request.bookId())) {
            String bookId = !isBlank(request.bookId()) ? request.bookId().trim() : assignment.getBookId();
            replaceAssignmentChapters(assignment, bookId, resolveRequestedChapterIds(request, assignment));
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
        if (request.quizSource() != null || request.quizRequired() != null || requestTouchesChapters(request)) {
            assignment.setQuizSource(resolveQuizSource(assignment, request, false));
        }
        applyQuizPassRulesOnUpdate(assignment, request, previousChapterIds, previousAvailableFrom);
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

    private void applyQuizPassRulesOnCreate(AssignmentEntity assignment, AssignmentWriteRequest request) {
        if (!assignment.isQuizRequired()) {
            assignment.setQuizPassMinCorrect(null);
            assignment.setQuizMaxRetries(null);
            assignment.setQuizRulesActivatedAt(null);
            return;
        }
        assignment.setQuizPassMinCorrect(request.quizPassMinCorrect());
        assignment.setQuizMaxRetries(request.quizMaxRetries());
        String status = !isBlank(request.status())
                ? request.status().trim().toUpperCase()
                : assignment.getStatus();
        if (request.quizPassMinCorrect() != null && request.quizMaxRetries() != null
                && "PUBLISHED".equalsIgnoreCase(status)) {
            assignment.setQuizRulesActivatedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        }
    }

    private void applyQuizPassRulesOnUpdate(
            AssignmentEntity assignment,
            AssignmentWriteRequest request,
            List<String> previousChapterIds,
            LocalDate previousAvailableFrom) {
        boolean quizRequired = request.quizRequired() != null
                ? request.quizRequired()
                : assignment.isQuizRequired();
        if (!quizRequired || Boolean.TRUE.equals(request.clearQuizPassRules())) {
            assignment.setQuizPassMinCorrect(null);
            assignment.setQuizMaxRetries(null);
            assignment.setQuizRulesActivatedAt(null);
            return;
        }
        Integer previousMin = assignment.getQuizPassMinCorrect();
        Integer previousRetries = assignment.getQuizMaxRetries();
        if (request.quizPassMinCorrect() != null) {
            assignment.setQuizPassMinCorrect(request.quizPassMinCorrect());
        }
        if (request.quizMaxRetries() != null) {
            assignment.setQuizMaxRetries(request.quizMaxRetries());
        }
        String nextStatus = !isBlank(request.status())
                ? request.status().trim().toUpperCase()
                : assignment.getStatus();
        boolean hasRules = assignment.getQuizPassMinCorrect() != null && assignment.getQuizMaxRetries() != null;
        boolean rulesChanged = !Objects.equals(previousMin, assignment.getQuizPassMinCorrect())
                || !Objects.equals(previousRetries, assignment.getQuizMaxRetries());
        List<String> nextChapterIds = assignment.getChapters().stream()
                .map(AssignmentChapterEntity::getChapterId)
                .toList();
        boolean chapterChanged = !previousChapterIds.equals(nextChapterIds);
        boolean publishedNow = "PUBLISHED".equalsIgnoreCase(nextStatus)
                && !"PUBLISHED".equalsIgnoreCase(assignment.getStatus());
        LocalDate nextAvailableFrom = assignment.getAvailableFromDate();
        boolean availabilityOpenedEarlier = previousAvailableFrom != null
                && (nextAvailableFrom == null || nextAvailableFrom.isBefore(previousAvailableFrom));
        if (hasRules
                && (rulesChanged || chapterChanged || publishedNow || availabilityOpenedEarlier
                || assignment.getQuizRulesActivatedAt() == null)
                && "PUBLISHED".equalsIgnoreCase(nextStatus)) {
            assignment.setQuizRulesActivatedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        }
    }

    private void validateQuizPassRulesForCreate(String termId, AssignmentWriteRequest request) {
        boolean quizRequired = Boolean.TRUE.equals(request.quizRequired());
        validateQuizPassRulePair(
                quizRequired,
                request.quizPassMinCorrect(),
                request.quizMaxRetries(),
                false);
        if (quizRequired) {
            validatePassMinAgainstEffectiveQuiz(
                    termId,
                    resolveRequestedChapterIds(request, null),
                    request.quizSource(),
                    null,
                    request.quizPassMinCorrect());
        }
    }

    private void validateQuizPassRulesForUpdate(AssignmentWriteRequest request, AssignmentEntity existing) {
        boolean quizRequired = request.quizRequired() != null
                ? request.quizRequired()
                : existing.isQuizRequired();
        if (!quizRequired || Boolean.TRUE.equals(request.clearQuizPassRules())) {
            return;
        }
        Integer min = request.quizPassMinCorrect() != null
                ? request.quizPassMinCorrect()
                : existing.getQuizPassMinCorrect();
        Integer retries = request.quizMaxRetries() != null
                ? request.quizMaxRetries()
                : existing.getQuizMaxRetries();
        boolean touching = request.quizPassMinCorrect() != null
                || request.quizMaxRetries() != null
                || Boolean.TRUE.equals(request.quizRequired());
        if (!touching && min == null && retries == null) {
            return;
        }
        validateQuizPassRulePair(quizRequired, min, retries, true);
        List<String> chapterIds = resolveRequestedChapterIds(request, existing);
        String quizSource = !isBlank(request.quizSource())
                ? request.quizSource().trim().toUpperCase()
                : existing.getQuizSource();
        validatePassMinAgainstEffectiveQuiz(existing.getTermId(), chapterIds, quizSource, existing.getId(), min);
    }

    private void validateQuizPassRulePair(
            boolean quizRequired,
            Integer minCorrect,
            Integer maxRetries,
            boolean allowBothNull) {
        if (!quizRequired) {
            if (minCorrect != null || maxRetries != null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Quiz pass rules require quizRequired=true.");
            }
            return;
        }
        if (minCorrect == null && maxRetries == null) {
            return;
        }
        if (minCorrect == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "quizPassMinCorrect is required when quizMaxRetries is set.");
        }
        if (maxRetries == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "quizMaxRetries is required when quizPassMinCorrect is set (use 0 for no retries).");
        }
        if (minCorrect < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "quizPassMinCorrect must be at least 1.");
        }
        if (maxRetries < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "quizMaxRetries cannot be negative.");
        }
        if (maxRetries > 20) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "quizMaxRetries cannot exceed 20.");
        }
    }

    private void validatePassMinAgainstEffectiveQuiz(
            String termId,
            List<String> chapterIds,
            String quizSource,
            String assignmentId,
            Integer minCorrect) {
        if (minCorrect == null) {
            return;
        }
        Optional<Integer> known = resolveEffectiveQuizQuestionCount(termId, chapterIds, quizSource, assignmentId);
        if (known.isEmpty()) {
            return;
        }
        if (minCorrect > known.get()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "quizPassMinCorrect (" + minCorrect + ") cannot exceed the effective quiz size ("
                            + known.get() + " questions).");
        }
    }

    Optional<Integer> resolveEffectiveQuizQuestionCount(
            String termId,
            List<String> chapterIds,
            String quizSource,
            String assignmentId) {
        if (AssignmentEntity.QUIZ_SOURCE_CUSTOM.equalsIgnoreCase(quizSource) && assignmentId != null) {
            return assignmentQuizRepository.findByAssignmentId(assignmentId)
                    .map(row -> classroomEffectiveQuizService.countQuestions(row.getPayloadJson()))
                    .filter(count -> count > 0);
        }
        if (chapterIds != null && chapterIds.size() == 1) {
            String chapterId = chapterIds.get(0);
            chapterQuizService.lockQuizContent(chapterId);
            return classroomEffectiveQuizService.resolveEffectiveQuestionCount(termId, chapterId);
        }
        return Optional.empty();
    }

    private void validatePublishedQuizRequirement(AssignmentEntity assignment) {
        if (!assignment.isQuizRequired() || !"PUBLISHED".equalsIgnoreCase(assignment.getStatus())) {
            return;
        }
        String source = assignment.getQuizSource();
        if (AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(source)) {
            String chapterId = assignment.singleChapterId();
            if (chapterId == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "quizSource=CHAPTER requires a single-chapter assignment.");
            }
            Optional<Integer> count = classroomEffectiveQuizService.resolveEffectiveQuestionCount(
                    assignment.getTermId(), chapterId);
            if (count.isEmpty() || count.get() < 1) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "No default chapter quiz is available. Define an assignment quiz before publishing.");
            }
            return;
        }
        if (AssignmentEntity.QUIZ_SOURCE_CUSTOM.equalsIgnoreCase(source)) {
            if (!assignmentQuizRepository.existsByAssignmentId(assignment.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Define an assignment quiz before publishing a quiz-required assignment.");
            }
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Quiz-required published assignments must use quizSource CHAPTER or CUSTOM.");
    }

    private void validateAssignmentForCreate(AssignmentWriteRequest request) {
        if (request == null || isBlank(request.title()) || isBlank(request.bookId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title and bookId are required.");
        }
        validateBookAndChapters(request.bookId().trim(), resolveRequestedChapterIds(request, null));
        validateAssignmentStatus(request.status());
        validateQuizSourceValue(request.quizSource());
    }

    private void validateAssignmentForUpdate(AssignmentWriteRequest request, AssignmentEntity existing) {
        String effectiveBookId = !isBlank(request.bookId()) ? request.bookId().trim() : existing.getBookId();
        if (!isBlank(request.bookId()) && !bookRepository.existsById(effectiveBookId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown bookId.");
        }
        if (requestTouchesChapters(request) || !isBlank(request.bookId())) {
            validateBookAndChapters(effectiveBookId, resolveRequestedChapterIds(request, existing));
        }
        validateAssignmentStatus(request.status());
        validateQuizSourceValue(request.quizSource());
    }

    private void validateBookAndChapters(String bookId, List<String> chapterIds) {
        if (!bookRepository.existsById(bookId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown bookId.");
        }
        if (chapterIds == null || chapterIds.isEmpty()) {
            return;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(chapterIds);
        for (String chapterId : unique) {
            Optional<ChapterEntity> chapter = chapterRepository.findByIdWithBook(chapterId);
            if (chapter.isEmpty() || chapter.get().getBook() == null
                    || !Objects.equals(bookId, chapter.get().getBook().getId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "chapterId must belong to the given bookId.");
            }
        }
    }

    private void replaceAssignmentChapters(AssignmentEntity assignment, String bookId, List<String> chapterIds) {
        if (chapterIds == null || chapterIds.isEmpty()) {
            assignment.replaceChapters(List.of());
            return;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(chapterIds);
        Map<String, AssignmentChapterEntity> existingByChapterId = assignment.getChapters().stream()
                .filter(row -> row.getChapterId() != null && !row.getChapterId().isBlank())
                .collect(Collectors.toMap(
                        AssignmentChapterEntity::getChapterId,
                        row -> row,
                        (first, ignored) -> first));
        List<ChapterEntity> ordered = chapterRepository.findByBookIdOrderByChapterIndex(bookId);
        List<AssignmentChapterEntity> next = new ArrayList<>();
        int sort = 0;
        for (ChapterEntity chapter : ordered) {
            if (!unique.contains(chapter.getId())) {
                continue;
            }
            AssignmentChapterEntity row = existingByChapterId.get(chapter.getId());
            if (row == null) {
                row = new AssignmentChapterEntity();
                row.setChapterId(chapter.getId());
            }
            row.setChapterIndex(chapter.getChapterIndex());
            row.setSortOrder(sort++);
            next.add(row);
        }
        if (next.size() != unique.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "chapterId must belong to the given bookId.");
        }
        assignment.replaceChapters(next);
    }

    private List<String> resolveRequestedChapterIds(AssignmentWriteRequest request, AssignmentEntity existing) {
        if (request.chapterIds() != null) {
            return request.chapterIds().stream()
                    .map(ClassroomAdminService::trimToNull)
                    .filter(Objects::nonNull)
                    .toList();
        }
        if (request.chapterId() != null || request.chapterIndex() != null) {
            if (request.chapterId() != null && trimToNull(request.chapterId()) == null) {
                return List.of();
            }
            String bookId = existing != null && isBlank(request.bookId())
                    ? existing.getBookId()
                    : (request.bookId() == null ? null : request.bookId().trim());
            String resolved = resolveChapterId(bookId, trimToNull(request.chapterId()), request.chapterIndex());
            return resolved == null ? List.of() : List.of(resolved);
        }
        if (existing != null) {
            return existing.getChapters().stream()
                    .map(AssignmentChapterEntity::getChapterId)
                    .toList();
        }
        return List.of();
    }

    private boolean requestTouchesChapters(AssignmentWriteRequest request) {
        return request.chapterIds() != null || request.chapterId() != null || request.chapterIndex() != null;
    }

    private String resolveQuizSource(
            AssignmentEntity assignment, AssignmentWriteRequest request, boolean creating) {
        if (!assignment.isQuizRequired()) {
            return null;
        }
        if (!isBlank(request.quizSource())) {
            return request.quizSource().trim().toUpperCase();
        }
        if (!creating && assignment.getQuizSource() != null) {
            if (AssignmentEntity.QUIZ_SOURCE_CHAPTER.equals(assignment.getQuizSource())
                    && assignment.singleChapterId() == null) {
                return AssignmentEntity.QUIZ_SOURCE_CUSTOM;
            }
            return assignment.getQuizSource();
        }
        return assignment.singleChapterId() != null
                ? AssignmentEntity.QUIZ_SOURCE_CHAPTER
                : AssignmentEntity.QUIZ_SOURCE_CUSTOM;
    }

    private void validateQuizSourceValue(String quizSource) {
        if (isBlank(quizSource)) {
            return;
        }
        String normalized = quizSource.trim().toUpperCase();
        if (!AssignmentEntity.QUIZ_SOURCE_CHAPTER.equals(normalized)
                && !AssignmentEntity.QUIZ_SOURCE_CUSTOM.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid quizSource.");
        }
    }

    /** Prefer explicit chapterId; otherwise resolve durable id from bookId + chapterIndex. */
    private String resolveChapterId(String bookId, String chapterId, Integer chapterIndex) {
        String explicit = trimToNull(chapterId);
        if (explicit != null) {
            return explicit;
        }
        if (bookId == null || bookId.isBlank() || chapterIndex == null) {
            return null;
        }
        return chapterRepository.findByBookIdAndChapterIndex(bookId, Math.max(0, chapterIndex))
                .map(ChapterEntity::getId)
                .orElse(null);
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
            applyQuizDefaults(features, override);
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
            Boolean readingBuddyEnabled,
            Integer defaultQuizQuestionCount,
            Integer defaultQuizPassMinCorrect,
            Integer defaultQuizMaxRetries,
            Integer defaultQuizOptionCount,
            Boolean clearDefaultQuizPassRules
    ) {
        public FeatureUpdateRequest(
                Boolean quizEnabled,
                Boolean recapEnabled,
                Boolean ttsEnabled,
                Boolean illustrationEnabled,
                Boolean characterEnabled,
                Boolean chatEnabled,
                Boolean speedReadingEnabled,
                Boolean readingBuddyEnabled) {
            this(
                    quizEnabled,
                    recapEnabled,
                    ttsEnabled,
                    illustrationEnabled,
                    characterEnabled,
                    chatEnabled,
                    speedReadingEnabled,
                    readingBuddyEnabled,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    public record AssignmentWriteRequest(
            String title,
            String bookId,
            List<String> chapterIds,
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
            Boolean clearAvailableFromDate,
            Integer quizPassMinCorrect,
            Integer quizMaxRetries,
            Boolean clearQuizPassRules,
            String quizSource
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
            this(title, bookId, null, chapterId, chapterIndex, dueDate, availableFromDate,
                    quizRequired, null, sortOrder, status, null, null, null, null, null, null);
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
            this(title, bookId, null, chapterId, chapterIndex, dueDate, availableFromDate,
                    quizRequired, characterChatRequired, sortOrder, status, null, null, null, null, null, null);
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
                String status,
                Boolean clearDueDate,
                Boolean clearAvailableFromDate) {
            this(title, bookId, null, chapterId, chapterIndex, dueDate, availableFromDate,
                    quizRequired, characterChatRequired, sortOrder, status,
                    clearDueDate, clearAvailableFromDate, null, null, null, null);
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
                String status,
                Boolean clearDueDate,
                Boolean clearAvailableFromDate,
                Integer quizPassMinCorrect,
                Integer quizMaxRetries,
                Boolean clearQuizPassRules) {
            this(title, bookId, null, chapterId, chapterIndex, dueDate, availableFromDate,
                    quizRequired, characterChatRequired, sortOrder, status,
                    clearDueDate, clearAvailableFromDate, quizPassMinCorrect, quizMaxRetries, clearQuizPassRules, null);
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
