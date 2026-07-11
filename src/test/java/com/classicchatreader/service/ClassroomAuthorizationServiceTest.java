package com.classicchatreader.service;

import com.classicchatreader.entity.ClassRoleMembershipEntity;
import com.classicchatreader.entity.ClassSectionEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.repository.ClassRoleMembershipRepository;
import com.classicchatreader.repository.ClassSectionRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.TermRepository;
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

    @Mock
    private TermRepository termRepository;

    @Mock
    private ClassSectionRepository classSectionRepository;

    private ClassroomAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new ClassroomAuthorizationService(
                enrollmentRepository,
                classRoleMembershipRepository,
                termRepository,
                classSectionRepository);
    }

    private void liveActiveTerm(String termId, String sectionId) {
        TermEntity term = new TermEntity();
        term.setId(termId);
        term.setClassSectionId(sectionId);
        term.setStatus("ACTIVE");
        when(termRepository.findByIdAndDeletedAtIsNull(termId)).thenReturn(Optional.of(term));
        ClassSectionEntity section = new ClassSectionEntity();
        section.setId(sectionId);
        section.setStatus("ACTIVE");
        when(classSectionRepository.findByIdAndDeletedAtIsNull(sectionId)).thenReturn(Optional.of(section));
    }

    @Test
    void teacherCanManageTerm() {
        liveActiveTerm("term-1", "sec-1");
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
        liveActiveTerm("term-1", "sec-1");
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
        liveActiveTerm("term-1", "sec-1");
        when(enrollmentRepository.findByTermIdAndUserIdAndDeletedAtIsNull("term-1", "x"))
                .thenReturn(Optional.empty());
        when(classRoleMembershipRepository.findByTermIdAndStatus("term-1", "ACTIVE"))
                .thenReturn(List.of());

        assertFalse(service.canViewTermContext("x", "term-1"));
        assertFalse(service.canManageTerm("x", "term-1"));
    }

    @Test
    void endedTermIsNotAuthorized() {
        TermEntity ended = new TermEntity();
        ended.setId("term-1");
        ended.setClassSectionId("sec-1");
        ended.setStatus("ENDED");
        when(termRepository.findByIdAndDeletedAtIsNull("term-1")).thenReturn(Optional.of(ended));

        assertFalse(service.canManageTerm("t1", "term-1"));
        assertFalse(service.canViewTermContext("s1", "term-1"));
    }

    @Test
    void softDeletedSectionIsNotAuthorized() {
        TermEntity term = new TermEntity();
        term.setId("term-1");
        term.setClassSectionId("sec-1");
        term.setStatus("ACTIVE");
        when(termRepository.findByIdAndDeletedAtIsNull("term-1")).thenReturn(Optional.of(term));
        when(classSectionRepository.findByIdAndDeletedAtIsNull("sec-1")).thenReturn(Optional.empty());

        assertFalse(service.isLiveActiveTerm("term-1"));
        assertFalse(service.canManageTerm("t1", "term-1"));
    }
}
