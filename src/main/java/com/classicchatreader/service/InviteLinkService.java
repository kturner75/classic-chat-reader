package com.classicchatreader.service;

import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.entity.InviteLinkEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.repository.ClassRoleMembershipRepository;
import com.classicchatreader.repository.ClassSectionRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.InviteLinkRepository;
import com.classicchatreader.repository.TermRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

@Service
public class InviteLinkService {

    private static final Set<String> REACTIVATABLE_STATUSES = Set.of("WITHDRAWN", "REMOVED");

    public enum RedeemStatus {
        SUCCESS,
        IDEMPOTENT,
        INVALID_CODE,
        EXPIRED,
        REVOKED,
        TERM_NOT_ACTIVE,
        MAX_USES,
        UNAUTHENTICATED,
        /** Enrollment exists but cannot rejoin via invite (e.g. COMPLETED after term rollover). */
        NOT_ELIGIBLE,
        /** User is already a teacher/TA on the term; invite is student-only (KD-18). */
        ALREADY_STAFF
    }

    private final InviteLinkRepository inviteLinkRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final TermRepository termRepository;
    private final ClassSectionRepository classSectionRepository;
    private final ClassRoleMembershipRepository classRoleMembershipRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public InviteLinkService(
            InviteLinkRepository inviteLinkRepository,
            EnrollmentRepository enrollmentRepository,
            TermRepository termRepository,
            ClassSectionRepository classSectionRepository,
            ClassRoleMembershipRepository classRoleMembershipRepository) {
        this.inviteLinkRepository = inviteLinkRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.termRepository = termRepository;
        this.classSectionRepository = classSectionRepository;
        this.classRoleMembershipRepository = classRoleMembershipRepository;
    }

    public IssuedInvite issue(String termId, String createdByUserId, String label, Integer maxUses, LocalDateTime expiresAt) {
        String rawCode = generateCode();
        InviteLinkEntity link = new InviteLinkEntity();
        link.setTermId(termId);
        link.setCodeHash(hashCode(rawCode));
        link.setCodeHint(hintFor(rawCode));
        link.setLabel(label);
        link.setMaxUses(maxUses);
        link.setUseCount(0);
        link.setExpiresAt(expiresAt);
        link.setCreatedByUserId(createdByUserId);
        inviteLinkRepository.save(link);
        return new IssuedInvite(link.getId(), rawCode, link.getCodeHint(), link.getTermId());
    }

    @Transactional
    public RedeemResult redeem(String rawCode, String userId) {
        if (userId == null || userId.isBlank()) {
            return new RedeemResult(RedeemStatus.UNAUTHENTICATED, null, null);
        }
        if (rawCode == null || rawCode.isBlank()) {
            return new RedeemResult(RedeemStatus.INVALID_CODE, null, null);
        }

        String codeHash = hashCode(rawCode.trim());
        Optional<InviteLinkEntity> linkOpt = inviteLinkRepository.findByCodeHashForUpdate(codeHash);
        if (linkOpt.isEmpty()) {
            return new RedeemResult(RedeemStatus.INVALID_CODE, null, null);
        }

        InviteLinkEntity link = linkOpt.get();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (link.getRevokedAt() != null) {
            return new RedeemResult(RedeemStatus.REVOKED, null, null);
        }
        if (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(now)) {
            return new RedeemResult(RedeemStatus.EXPIRED, null, null);
        }

        Optional<TermEntity> termOpt = termRepository.findByIdAndDeletedAtIsNull(link.getTermId());
        if (termOpt.isEmpty() || !"ACTIVE".equals(termOpt.get().getStatus())) {
            return new RedeemResult(RedeemStatus.TERM_NOT_ACTIVE, null, null);
        }
        TermEntity term = termOpt.get();
        boolean sectionLive = classSectionRepository.findByIdAndDeletedAtIsNull(term.getClassSectionId())
                .filter(s -> "ACTIVE".equals(s.getStatus()))
                .isPresent();
        if (!sectionLive) {
            // Soft-deleted or ARCHIVED parent section: treat as non-joinable.
            return new RedeemResult(RedeemStatus.TERM_NOT_ACTIVE, null, null);
        }

        // KD-18: teachers are not auto-enrolled as students; invite is student-only.
        boolean isStaff = classRoleMembershipRepository.findByTermIdAndStatus(link.getTermId(), "ACTIVE").stream()
                .anyMatch(m -> userId.equals(m.getUserId())
                        && ClassroomAuthorizationService.isTeacherLikeRole(m.getRole()));
        if (isStaff) {
            return new RedeemResult(RedeemStatus.ALREADY_STAFF, null, link.getTermId());
        }

        // Include soft-deleted rows to honor UNIQUE(term_id, user_id) and design reactivation path.
        Optional<EnrollmentEntity> existing =
                enrollmentRepository.findByTermIdAndUserId(link.getTermId(), userId);
        if (existing.isPresent()) {
            EnrollmentEntity enrollment = existing.get();
            if ("ACTIVE".equals(enrollment.getStatus()) && enrollment.getDeletedAt() == null) {
                return new RedeemResult(RedeemStatus.IDEMPOTENT, enrollment.getId(), link.getTermId());
            }
            if ("COMPLETED".equals(enrollment.getStatus())) {
                return new RedeemResult(RedeemStatus.NOT_ELIGIBLE, enrollment.getId(), link.getTermId());
            }
            boolean reactivatable = REACTIVATABLE_STATUSES.contains(enrollment.getStatus())
                    || enrollment.getDeletedAt() != null;
            if (!reactivatable) {
                return new RedeemResult(RedeemStatus.NOT_ELIGIBLE, enrollment.getId(), link.getTermId());
            }
            if (link.getMaxUses() != null && link.getUseCount() >= link.getMaxUses()) {
                return new RedeemResult(RedeemStatus.MAX_USES, null, null);
            }
            enrollment.setStatus("ACTIVE");
            enrollment.setDeletedAt(null);
            enrollment.setLeftDate(null);
            enrollment.setJoinedDate(LocalDate.now(ZoneOffset.UTC));
            enrollment.setInviteLinkId(link.getId());
            enrollmentRepository.save(enrollment);
            link.setUseCount(link.getUseCount() + 1);
            inviteLinkRepository.save(link);
            return new RedeemResult(RedeemStatus.SUCCESS, enrollment.getId(), link.getTermId());
        }

        if (link.getMaxUses() != null && link.getUseCount() >= link.getMaxUses()) {
            return new RedeemResult(RedeemStatus.MAX_USES, null, null);
        }

        EnrollmentEntity enrollment = new EnrollmentEntity();
        enrollment.setTermId(link.getTermId());
        enrollment.setUserId(userId);
        enrollment.setRole("STUDENT");
        enrollment.setStatus("ACTIVE");
        enrollment.setJoinedDate(LocalDate.now(ZoneOffset.UTC));
        enrollment.setInviteLinkId(link.getId());
        enrollmentRepository.save(enrollment);

        link.setUseCount(link.getUseCount() + 1);
        inviteLinkRepository.save(link);

        return new RedeemResult(RedeemStatus.SUCCESS, enrollment.getId(), link.getTermId());
    }

    public static String hashCode(String rawCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawCode.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash invite code", e);
        }
    }

    private String generateCode() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hintFor(String rawCode) {
        if (rawCode == null || rawCode.length() < 4) {
            return rawCode;
        }
        return rawCode.substring(rawCode.length() - 4);
    }

    public record IssuedInvite(String inviteLinkId, String code, String codeHint, String termId) {
    }

    public record RedeemResult(RedeemStatus status, String enrollmentId, String termId) {
    }
}
