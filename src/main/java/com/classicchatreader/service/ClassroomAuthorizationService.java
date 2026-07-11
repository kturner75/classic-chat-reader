package com.classicchatreader.service;

import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.repository.ClassRoleMembershipRepository;
import com.classicchatreader.repository.ClassSectionRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.TermRepository;
import org.springframework.stereotype.Service;

/**
 * Deny-by-default classroom capability checks (BL-025.1).
 * School-admin education-record access is intentionally denied until BL-043.
 */
@Service
public class ClassroomAuthorizationService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String ROLE_TEACHER = "TEACHER";
    public static final String ROLE_CO_TEACHER = "CO_TEACHER";
    public static final String ROLE_TA = "TA";
    public static final String ROLE_STUDENT = "STUDENT";

    private final EnrollmentRepository enrollmentRepository;
    private final ClassRoleMembershipRepository classRoleMembershipRepository;
    private final TermRepository termRepository;
    private final ClassSectionRepository classSectionRepository;

    public ClassroomAuthorizationService(
            EnrollmentRepository enrollmentRepository,
            ClassRoleMembershipRepository classRoleMembershipRepository,
            TermRepository termRepository,
            ClassSectionRepository classSectionRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRoleMembershipRepository = classRoleMembershipRepository;
        this.termRepository = termRepository;
        this.classSectionRepository = classSectionRepository;
    }

    public boolean isActiveTeacherOnTerm(String userId, String termId) {
        if (userId == null || termId == null || !isLiveActiveTerm(termId)) {
            return false;
        }
        return classRoleMembershipRepository.findByTermIdAndStatus(termId, STATUS_ACTIVE).stream()
                .anyMatch(m -> userId.equals(m.getUserId()) && isTeacherLikeRole(m.getRole()));
    }

    public boolean isActiveStudentOnTerm(String userId, String termId) {
        if (userId == null || termId == null || !isLiveActiveTerm(termId)) {
            return false;
        }
        return enrollmentRepository
                .findByTermIdAndUserIdAndDeletedAtIsNull(termId, userId)
                .filter(e -> STATUS_ACTIVE.equals(e.getStatus()))
                .isPresent();
    }

    public boolean canManageTerm(String userId, String termId) {
        return isActiveTeacherOnTerm(userId, termId);
    }

    public boolean canViewTermContext(String userId, String termId) {
        return isActiveTeacherOnTerm(userId, termId) || isActiveStudentOnTerm(userId, termId);
    }

    public String primaryRoleOnTerm(String userId, String termId) {
        if (isActiveTeacherOnTerm(userId, termId)) {
            return ROLE_TEACHER;
        }
        if (isActiveStudentOnTerm(userId, termId)) {
            return ROLE_STUDENT;
        }
        return null;
    }

    /**
     * Term must exist, not be soft-deleted, be ACTIVE, and belong to a non-deleted class section.
     * Aligns with {@link ClassroomContextService} membership filtering.
     */
    public boolean isLiveActiveTerm(String termId) {
        if (termId == null) {
            return false;
        }
        return termRepository.findByIdAndDeletedAtIsNull(termId)
                .filter(t -> STATUS_ACTIVE.equals(t.getStatus()))
                .filter(this::hasLiveSection)
                .isPresent();
    }

    private boolean hasLiveSection(TermEntity term) {
        return classSectionRepository.findByIdAndDeletedAtIsNull(term.getClassSectionId())
                .filter(s -> STATUS_ACTIVE.equals(s.getStatus()))
                .isPresent();
    }

    public static boolean isTeacherLikeRole(String role) {
        return ROLE_TEACHER.equals(role) || ROLE_CO_TEACHER.equals(role) || ROLE_TA.equals(role);
    }
}
