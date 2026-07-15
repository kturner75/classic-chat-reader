package com.classicchatreader.service;

import com.classicchatreader.repository.AccountCapabilityRepository;
import com.classicchatreader.repository.ClassRoleMembershipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClassroomTeacherCapabilityService {

    public static final String CAPABILITY_CREATE_CLASSROOM = "CREATE_CLASSROOM";

    private final AccountCapabilityRepository accountCapabilityRepository;
    private final ClassRoleMembershipRepository classRoleMembershipRepository;

    public ClassroomTeacherCapabilityService(
            AccountCapabilityRepository accountCapabilityRepository,
            ClassRoleMembershipRepository classRoleMembershipRepository) {
        this.accountCapabilityRepository = accountCapabilityRepository;
        this.classRoleMembershipRepository = classRoleMembershipRepository;
    }

    @Transactional(readOnly = true)
    public TeacherCapabilities getCapabilities(String userId) {
        if (userId == null || userId.isBlank()) {
            return TeacherCapabilities.none();
        }

        boolean canCreateClass = accountCapabilityRepository.existsByUserIdAndCapabilityAndStatus(
                userId,
                CAPABILITY_CREATE_CLASSROOM,
                ClassroomAuthorizationService.STATUS_ACTIVE
        );
        boolean hasTeacherMembership = classRoleMembershipRepository
                .findByUserIdAndStatus(userId, ClassroomAuthorizationService.STATUS_ACTIVE).stream()
                .anyMatch(membership -> ClassroomAuthorizationService.isTeacherLikeRole(membership.getRole()));
        return new TeacherCapabilities(canCreateClass || hasTeacherMembership, canCreateClass);
    }

    public void requireCanCreateClass(String userId) {
        if (!getCapabilities(userId).canCreateClass()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Teaching access is not enabled for this account."
            );
        }
    }

    public record TeacherCapabilities(boolean canTeach, boolean canCreateClass) {
        public static TeacherCapabilities none() {
            return new TeacherCapabilities(false, false);
        }
    }
}
