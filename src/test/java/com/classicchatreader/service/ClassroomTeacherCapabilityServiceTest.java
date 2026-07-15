package com.classicchatreader.service;

import com.classicchatreader.entity.ClassRoleMembershipEntity;
import com.classicchatreader.repository.AccountCapabilityRepository;
import com.classicchatreader.repository.ClassRoleMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassroomTeacherCapabilityServiceTest {

    @Mock private AccountCapabilityRepository accountCapabilityRepository;
    @Mock private ClassRoleMembershipRepository classRoleMembershipRepository;

    private ClassroomTeacherCapabilityService service;

    @BeforeEach
    void setUp() {
        service = new ClassroomTeacherCapabilityService(
                accountCapabilityRepository,
                classRoleMembershipRepository
        );
    }

    @Test
    void createCapabilityGrantsTeachingAndClassCreation() {
        when(accountCapabilityRepository.existsByUserIdAndCapabilityAndStatus(
                "teacher-1",
                ClassroomTeacherCapabilityService.CAPABILITY_CREATE_CLASSROOM,
                ClassroomAuthorizationService.STATUS_ACTIVE
        )).thenReturn(true);
        when(classRoleMembershipRepository.findByUserIdAndStatus(
                "teacher-1", ClassroomAuthorizationService.STATUS_ACTIVE)).thenReturn(List.of());

        ClassroomTeacherCapabilityService.TeacherCapabilities result = service.getCapabilities("teacher-1");

        assertTrue(result.canTeach());
        assertTrue(result.canCreateClass());
    }

    @Test
    void existingTeacherMembershipGrantsWorkspaceButNotNewClassCreation() {
        when(accountCapabilityRepository.existsByUserIdAndCapabilityAndStatus(
                "teacher-1",
                ClassroomTeacherCapabilityService.CAPABILITY_CREATE_CLASSROOM,
                ClassroomAuthorizationService.STATUS_ACTIVE
        )).thenReturn(false);
        ClassRoleMembershipEntity membership = new ClassRoleMembershipEntity();
        membership.setUserId("teacher-1");
        membership.setRole(ClassroomAuthorizationService.ROLE_TEACHER);
        membership.setStatus(ClassroomAuthorizationService.STATUS_ACTIVE);
        when(classRoleMembershipRepository.findByUserIdAndStatus(
                "teacher-1", ClassroomAuthorizationService.STATUS_ACTIVE)).thenReturn(List.of(membership));

        ClassroomTeacherCapabilityService.TeacherCapabilities result = service.getCapabilities("teacher-1");

        assertTrue(result.canTeach());
        assertFalse(result.canCreateClass());
    }

    @Test
    void ordinaryStudentHasNoTeachingCapability() {
        when(accountCapabilityRepository.existsByUserIdAndCapabilityAndStatus(
                "student-1",
                ClassroomTeacherCapabilityService.CAPABILITY_CREATE_CLASSROOM,
                ClassroomAuthorizationService.STATUS_ACTIVE
        )).thenReturn(false);
        when(classRoleMembershipRepository.findByUserIdAndStatus(
                "student-1", ClassroomAuthorizationService.STATUS_ACTIVE)).thenReturn(List.of());

        ClassroomTeacherCapabilityService.TeacherCapabilities result = service.getCapabilities("student-1");

        assertFalse(result.canTeach());
        assertFalse(result.canCreateClass());
        assertThrows(ResponseStatusException.class, () -> service.requireCanCreateClass("student-1"));
    }

    @Test
    void anonymousReaderHasNoTeachingCapability() {
        ClassroomTeacherCapabilityService.TeacherCapabilities result = service.getCapabilities(null);

        assertFalse(result.canTeach());
        assertFalse(result.canCreateClass());
    }
}
