package com.classicchatreader.service;

import com.classicchatreader.repository.ClassRoleMembershipRepository;
import com.classicchatreader.repository.EnrollmentRepository;
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

    public ClassroomAuthorizationService(
            EnrollmentRepository enrollmentRepository,
            ClassRoleMembershipRepository classRoleMembershipRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRoleMembershipRepository = classRoleMembershipRepository;
    }

    public boolean isActiveTeacherOnTerm(String userId, String termId) {
        if (userId == null || termId == null) {
            return false;
        }
        return classRoleMembershipRepository.findByTermIdAndStatus(termId, STATUS_ACTIVE).stream()
                .anyMatch(m -> userId.equals(m.getUserId()) && isTeacherLikeRole(m.getRole()));
    }

    public boolean isActiveStudentOnTerm(String userId, String termId) {
        if (userId == null || termId == null) {
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

    public static boolean isTeacherLikeRole(String role) {
        return ROLE_TEACHER.equals(role) || ROLE_CO_TEACHER.equals(role) || ROLE_TA.equals(role);
    }
}
