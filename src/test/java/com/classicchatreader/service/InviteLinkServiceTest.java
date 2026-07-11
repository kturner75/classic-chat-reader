package com.classicchatreader.service;

import com.classicchatreader.entity.ClassSectionEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.entity.InviteLinkEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.entity.ClassRoleMembershipEntity;
import com.classicchatreader.repository.ClassRoleMembershipRepository;
import com.classicchatreader.repository.ClassSectionRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.InviteLinkRepository;
import com.classicchatreader.repository.TermRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InviteLinkServiceTest {

    @Mock
    private InviteLinkRepository inviteLinkRepository;

    @Mock
    private EnrollmentRepository enrollmentRepository;

    @Mock
    private TermRepository termRepository;

    @Mock
    private ClassSectionRepository classSectionRepository;

    @Mock
    private ClassRoleMembershipRepository classRoleMembershipRepository;

    private InviteLinkService inviteLinkService;

    @BeforeEach
    void setUp() {
        inviteLinkService = new InviteLinkService(
                inviteLinkRepository,
                enrollmentRepository,
                termRepository,
                classSectionRepository,
                classRoleMembershipRepository);
    }

    private void stubLiveTerm(String termId, String sectionId) {
        TermEntity term = new TermEntity();
        term.setId(termId);
        term.setClassSectionId(sectionId);
        term.setStatus("ACTIVE");
        when(termRepository.findByIdAndDeletedAtIsNull(termId)).thenReturn(Optional.of(term));
        ClassSectionEntity section = new ClassSectionEntity();
        section.setId(sectionId);
        section.setStatus("ACTIVE");
        when(classSectionRepository.findByIdAndDeletedAtIsNull(sectionId)).thenReturn(Optional.of(section));
        when(classRoleMembershipRepository.findByTermIdAndStatus(termId, "ACTIVE")).thenReturn(List.of());
    }

    @Test
    void issuePersistsHashNotRawCode() {
        when(inviteLinkRepository.save(any(InviteLinkEntity.class))).thenAnswer(inv -> {
            InviteLinkEntity link = inv.getArgument(0);
            link.setId("link-1");
            return link;
        });

        InviteLinkService.IssuedInvite issued = inviteLinkService.issue("term-1", "user-1", "Default", null, null);

        assertNotNull(issued.code());
        assertEquals("link-1", issued.inviteLinkId());
        ArgumentCaptor<InviteLinkEntity> captor = ArgumentCaptor.forClass(InviteLinkEntity.class);
        verify(inviteLinkRepository).save(captor.capture());
        assertEquals(InviteLinkService.hashCode(issued.code()), captor.getValue().getCodeHash());
        assertNull(captor.getValue().getRevokedAt());
    }

    @Test
    void redeemCreatesEnrollmentAndIncrementsUseCount() {
        String raw = "test-code-value-xx";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setUseCount(0);

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));
        stubLiveTerm("term-1", "sec-1");
        when(enrollmentRepository.findByTermIdAndUserId("term-1", "user-1"))
                .thenReturn(Optional.empty());
        when(enrollmentRepository.saveAndFlush(any(EnrollmentEntity.class))).thenAnswer(inv -> {
            EnrollmentEntity e = inv.getArgument(0);
            e.setId("enr-1");
            return e;
        });
        when(inviteLinkRepository.save(any(InviteLinkEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.SUCCESS, result.status());
        assertEquals("enr-1", result.enrollmentId());
        assertEquals(1, link.getUseCount());
    }

    @Test
    void redeemIsIdempotentForActiveEnrollment() {
        String raw = "already-joined";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setUseCount(3);

        EnrollmentEntity enrollment = new EnrollmentEntity();
        enrollment.setId("enr-1");
        enrollment.setStatus("ACTIVE");

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));
        stubLiveTerm("term-1", "sec-1");
        when(enrollmentRepository.findByTermIdAndUserId("term-1", "user-1"))
                .thenReturn(Optional.of(enrollment));

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.IDEMPOTENT, result.status());
        assertEquals(3, link.getUseCount());
        verify(enrollmentRepository, never()).save(any());
        verify(inviteLinkRepository, never()).save(eq(link));
    }

    @Test
    void redeemDoesNotReactivateCompletedEnrollment() {
        String raw = "completed-student";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setUseCount(1);

        EnrollmentEntity enrollment = new EnrollmentEntity();
        enrollment.setId("enr-1");
        enrollment.setStatus("COMPLETED");

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));
        stubLiveTerm("term-1", "sec-1");
        when(enrollmentRepository.findByTermIdAndUserId("term-1", "user-1"))
                .thenReturn(Optional.of(enrollment));

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.NOT_ELIGIBLE, result.status());
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void redeemReactivatesSoftDeletedEnrollment() {
        String raw = "soft-deleted";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setUseCount(0);

        EnrollmentEntity enrollment = new EnrollmentEntity();
        enrollment.setId("enr-1");
        enrollment.setStatus("WITHDRAWN");
        enrollment.setDeletedAt(java.time.LocalDateTime.now());

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));
        stubLiveTerm("term-1", "sec-1");
        when(enrollmentRepository.findByTermIdAndUserId("term-1", "user-1"))
                .thenReturn(Optional.of(enrollment));
        when(enrollmentRepository.save(any(EnrollmentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inviteLinkRepository.save(any(InviteLinkEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.SUCCESS, result.status());
        assertEquals("ACTIVE", enrollment.getStatus());
        assertNull(enrollment.getDeletedAt());
        assertEquals(1, link.getUseCount());
    }

    @Test
    void redeemRejectsWhenParentSectionSoftDeleted() {
        String raw = "deleted-section";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));

        TermEntity term = new TermEntity();
        term.setId("term-1");
        term.setClassSectionId("sec-1");
        term.setStatus("ACTIVE");

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));
        when(termRepository.findByIdAndDeletedAtIsNull("term-1")).thenReturn(Optional.of(term));
        when(classSectionRepository.findByIdAndDeletedAtIsNull("sec-1")).thenReturn(Optional.empty());

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.TERM_NOT_ACTIVE, result.status());
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void redeemRejectsTeacherStaff() {
        String raw = "teacher-code";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));
        stubLiveTerm("term-1", "sec-1");

        ClassRoleMembershipEntity membership = new ClassRoleMembershipEntity();
        membership.setUserId("user-1");
        membership.setRole("TEACHER");
        membership.setStatus("ACTIVE");
        when(classRoleMembershipRepository.findByTermIdAndStatus("term-1", "ACTIVE"))
                .thenReturn(List.of(membership));

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.ALREADY_STAFF, result.status());
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void redeemRequiresAuth() {
        InviteLinkService.RedeemResult result = inviteLinkService.redeem("code", null);
        assertEquals(InviteLinkService.RedeemStatus.UNAUTHENTICATED, result.status());
    }
}
