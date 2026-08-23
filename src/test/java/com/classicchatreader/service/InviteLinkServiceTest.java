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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    private static void boundInvite(InviteLinkEntity link) {
        link.setMaxUses(40);
        link.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(30));
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
        boundInvite(link);

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
        boundInvite(link);

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
        boundInvite(link);

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
        boundInvite(link);

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
    void softDeletedActiveEnrollmentIsIdempotentWithoutConsumingUse() {
        String raw = "active-soft-deleted";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setUseCount(2);
        boundInvite(link);

        EnrollmentEntity enrollment = new EnrollmentEntity();
        enrollment.setId("enr-1");
        enrollment.setStatus("ACTIVE");
        enrollment.setDeletedAt(java.time.LocalDateTime.now());

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));
        stubLiveTerm("term-1", "sec-1");
        when(enrollmentRepository.findByTermIdAndUserId("term-1", "user-1"))
                .thenReturn(Optional.of(enrollment));
        when(enrollmentRepository.save(any(EnrollmentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.IDEMPOTENT, result.status());
        assertNull(enrollment.getDeletedAt());
        assertEquals(2, link.getUseCount());
    }

    @Test
    void redeemRejectsWhenParentSectionSoftDeleted() {
        String raw = "deleted-section";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        boundInvite(link);

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
        boundInvite(link);

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

    @Test
    void redeemRejectsExpiredCode() {
        String raw = "expired-code";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.EXPIRED, result.status());
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void redeemRejectsRevokedCode() {
        String raw = "revoked-code";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.REVOKED, result.status());
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void redeemRejectsWhenMaxUsesReached() {
        String raw = "maxed-code";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setMaxUses(2);
        link.setUseCount(2);
        link.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(30));

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));
        stubLiveTerm("term-1", "sec-1");
        when(enrollmentRepository.findByTermIdAndUserId("term-1", "user-1"))
                .thenReturn(Optional.empty());

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.MAX_USES, result.status());
        verify(enrollmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void redeemRejectsUnrestrictedExpiry() {
        String raw = "legacy-no-expiry";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setMaxUses(40);
        link.setUseCount(0);
        link.setExpiresAt(null);

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.EXPIRED, result.status());
        verify(enrollmentRepository, never()).save(any());
        verify(enrollmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void redeemRejectsUnrestrictedMaxUses() {
        String raw = "legacy-no-max";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setMaxUses(null);
        link.setUseCount(0);
        link.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(30));

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));
        stubLiveTerm("term-1", "sec-1");
        when(enrollmentRepository.findByTermIdAndUserId("term-1", "user-1"))
                .thenReturn(Optional.empty());

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.MAX_USES, result.status());
        verify(enrollmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void redeemRejectsHistoricalUnrestrictedInvite() {
        String raw = "legacy-unbounded";
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        link.setCodeHash(InviteLinkService.hashCode(raw));
        link.setMaxUses(null);
        link.setExpiresAt(null);
        link.setUseCount(0);

        when(inviteLinkRepository.findByCodeHashForUpdate(InviteLinkService.hashCode(raw)))
                .thenReturn(Optional.of(link));

        InviteLinkService.RedeemResult result = inviteLinkService.redeem(raw, "user-1");

        assertEquals(InviteLinkService.RedeemStatus.EXPIRED, result.status());
        verify(enrollmentRepository, never()).save(any());
        verify(enrollmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void issueReplacingActiveRevokesPreviousLink() {
        InviteLinkEntity previous = new InviteLinkEntity();
        previous.setId("old-link");
        previous.setTermId("term-1");
        when(inviteLinkRepository.findByTermIdAndRevokedAtIsNullOrderByCreatedAtDesc("term-1"))
                .thenReturn(List.of(previous));
        when(inviteLinkRepository.save(any(InviteLinkEntity.class))).thenAnswer(inv -> {
            InviteLinkEntity link = inv.getArgument(0);
            if (link.getId() == null) {
                link.setId("new-link");
            }
            return link;
        });

        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(30);
        InviteLinkService.IssuedInvite issued =
                inviteLinkService.issueReplacingActive("term-1", "teacher-1", "Rotate", 40, expiresAt);

        assertEquals("new-link", issued.inviteLinkId());
        assertEquals(40, issued.maxUses());
        assertNotNull(previous.getRevokedAt());
        assertEquals("new-link", previous.getReplacedByLinkId());
    }

    @Test
    void revokeSetsRevokedAt() {
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        inviteLinkService.revoke(link);
        assertNotNull(link.getRevokedAt());
        verify(inviteLinkRepository).save(link);
    }
}
