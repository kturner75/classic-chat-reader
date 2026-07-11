package com.classicchatreader.service;

import com.classicchatreader.entity.ClassRoleMembershipEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.repository.ClassRoleMembershipRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassroomAuthorizationServiceTest {

    @Mock
    private EnrollmentRepository enrollmentRepository;

    @Mock
    private ClassRoleMembershipRepository classRoleMembershipRepository;

    private ClassroomAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new ClassroomAuthorizationService(enrollmentRepository, classRoleMembershipRepository);
    }

    @Test
    void teacherCanManageTerm() {
        ClassRoleMembershipEntity membership = new ClassRoleMembershipEntity();
        membership.setUserId("t1");
        membership.setRole("TEACHER");
        membership.setStatus("ACTIVE");
        when(classRoleMembershipRepository.findByTermIdAndStatus("term-1", "ACTIVE"))
                .thenReturn(List.of(membership));

        assertTrue(service.canManageTerm("t1", "term-1"));
        assertEquals("TEACHER", service.primaryRoleOnTerm("t1", "term-1"));
    }

    @Test
    void studentCanViewButNotManage() {
        EnrollmentEntity enrollment = new EnrollmentEntity();
        enrollment.setUserId("s1");
        enrollment.setStatus("ACTIVE");
        when(enrollmentRepository.findByTermIdAndUserIdAndDeletedAtIsNull("term-1", "s1"))
                .thenReturn(Optional.of(enrollment));
        when(classRoleMembershipRepository.findByTermIdAndStatus("term-1", "ACTIVE"))
                .thenReturn(List.of());

        assertTrue(service.canViewTermContext("s1", "term-1"));
        assertFalse(service.canManageTerm("s1", "term-1"));
        assertEquals("STUDENT", service.primaryRoleOnTerm("s1", "term-1"));
    }

    @Test
    void denyByDefaultForUnrelatedUser() {
        when(enrollmentRepository.findByTermIdAndUserIdAndDeletedAtIsNull("term-1", "x"))
                .thenReturn(Optional.empty());
        when(classRoleMembershipRepository.findByTermIdAndStatus("term-1", "ACTIVE"))
                .thenReturn(List.of());

        assertFalse(service.canViewTermContext("x", "term-1"));
        assertFalse(service.canManageTerm("x", "term-1"));
    }
}
