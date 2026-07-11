package com.classicchatreader.service;

import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.ClassFeatureSettingsEntity;
import com.classicchatreader.entity.ClassRoleMembershipEntity;
import com.classicchatreader.entity.ClassSectionEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.BookRepository;
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
import java.util.Optional;

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
    private final UserRepository userRepository;

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
            UserRepository userRepository) {
        this.classSectionRepository = classSectionRepository;
        this.termRepository = termRepository;
        this.classRoleMembershipRepository = classRoleMembershipRepository;
        this.classFeatureSettingsRepository = classFeatureSettingsRepository;
        this.assignmentRepository = assignmentRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.inviteLinkService = inviteLinkService;
        this.authorizationService = authorizationService;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public CreateClassResult createClass(String ownerUserId, CreateClassRequest request) {
        requireUser(ownerUserId);
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
                    Optional<TermEntity> active = termRepository
                            .findByClassSectionIdAndStatusAndDeletedAtIsNull(section.getId(), "ACTIVE");
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
        validateAssignment(request);
        AssignmentEntity assignment = new AssignmentEntity();
        applyAssignment(assignment, termId, userId, request);
        if (isBlank(assignment.getStatus())) {
            assignment.setStatus("DRAFT");
        }
        return assignmentRepository.save(assignment);
    }

    @Transactional
    public AssignmentEntity updateAssignment(String userId, String assignmentId, AssignmentWriteRequest request) {
        AssignmentEntity assignment = assignmentRepository.findByIdAndDeletedAtIsNull(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found."));
        requireTeacher(userId, assignment.getTermId());
        validateAssignment(request);
        applyAssignment(assignment, assignment.getTermId(), userId, request);
        return assignmentRepository.save(assignment);
    }

    @Transactional(readOnly = true)
    public List<AssignmentEntity> listAssignments(String userId, String termId, boolean publishedOnly) {
        if (!authorizationService.canViewTermContext(userId, termId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this term.");
        }
        if (publishedOnly) {
            return assignmentRepository
                    .findByTermIdAndStatusAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(termId, "PUBLISHED");
        }
        return assignmentRepository.findByTermIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(termId);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentRow> listRoster(String userId, String termId) {
        requireTeacher(userId, termId);
        return enrollmentRepository
                .findByTermIdAndStatusAndDeletedAtIsNullOrderByJoinedDateAsc(termId, "ACTIVE")
                .stream()
                .map(e -> new EnrollmentRow(
                        e.getId(),
                        e.getUserId(),
                        e.getStatus(),
                        e.getJoinedDate(),
                        e.getDisplayNameOverride()
                ))
                .toList();
    }

    private void applyAssignment(AssignmentEntity assignment, String termId, String userId, AssignmentWriteRequest request) {
        assignment.setTermId(termId);
        assignment.setTitle(request.title().trim());
        assignment.setBookId(request.bookId().trim());
        assignment.setChapterId(trimToNull(request.chapterId()));
        assignment.setChapterIndex(request.chapterIndex());
        assignment.setDueDate(request.dueDate());
        assignment.setAvailableFromDate(request.availableFromDate());
        assignment.setQuizRequired(Boolean.TRUE.equals(request.quizRequired()));
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

    private void validateAssignment(AssignmentWriteRequest request) {
        if (request == null || isBlank(request.title()) || isBlank(request.bookId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title and bookId are required.");
        }
        if (!bookRepository.existsById(request.bookId().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown bookId.");
        }
        if (!isBlank(request.status())) {
            String status = request.status().trim().toUpperCase();
            if (!status.equals("DRAFT") && !status.equals("PUBLISHED") && !status.equals("ARCHIVED")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid assignment status.");
            }
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
            Integer sortOrder,
            String status
    ) {
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
            String status,
            LocalDate joinedDate,
            String displayNameOverride
    ) {
    }
}
