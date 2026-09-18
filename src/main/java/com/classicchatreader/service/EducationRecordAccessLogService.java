package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.EducationRecordAccessLogEntity;
import com.classicchatreader.repository.EducationRecordAccessLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * FERPA education-record access audit (BL-043.5).
 *
 * <p>Writes are fail-closed: when the audit row cannot be stored the caller's read fails
 * rather than disclosing a student record with no audit trail. Self-access (actor is the
 * subject) is not logged; FERPA auditing covers access by someone else.
 */
@Service
public class EducationRecordAccessLogService {

    private final EducationRecordAccessLogRepository repository;
    private final ClassroomProperties classroomProperties;

    public EducationRecordAccessLogService(
            EducationRecordAccessLogRepository repository,
            ClassroomProperties classroomProperties) {
        this.repository = repository;
        this.classroomProperties = classroomProperties;
    }

    /**
     * Annotated as well as the collection overload: the internal call below bypasses Spring's
     * proxy, so this entry point must open the independent transaction itself.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAccess(
            String actorUserId,
            String subjectUserId,
            String termId,
            String accessType,
            String resourceType,
            String resourceId,
            HttpServletRequest request) {
        recordAccess(actorUserId, List.of(subjectUserId), termId, accessType, resourceType, resourceId, request);
    }

    /**
     * One row per subject: {@code subject_user_id} is NOT NULL, so a roster read of thirty
     * students records thirty accesses.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAccess(
            String actorUserId,
            Collection<String> subjectUserIds,
            String termId,
            String accessType,
            String resourceType,
            String resourceId,
            HttpServletRequest request) {
        write(actorUserId, subjectUserIds, termId, accessType, resourceType, resourceId, request);
    }

    /**
     * Joins the caller's transaction instead of committing independently. For writes whose audit
     * row references another row created in the same transaction (a chat export job): both commit
     * together or neither does. Requires an active transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAccessWithinTransaction(
            String actorUserId,
            String subjectUserId,
            String termId,
            String accessType,
            String resourceType,
            String resourceId,
            HttpServletRequest request) {
        write(actorUserId, List.of(subjectUserId), termId, accessType, resourceType, resourceId, request);
    }

    private void write(
            String actorUserId,
            Collection<String> subjectUserIds,
            String termId,
            String accessType,
            String resourceType,
            String resourceId,
            HttpServletRequest request) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalArgumentException("Education record access log needs an actor");
        }
        if (accessType == null || accessType.isBlank()) {
            throw new IllegalArgumentException("Education record access log needs an access type");
        }
        LocalDateTime occurredAt = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime retainUntil = occurredAt.plusDays(classroomProperties.accessLogRetainDays());
        String ipHash = RequestPrivacy.hash(RequestPrivacy.resolveClientIp(request));
        String userAgentHash = RequestPrivacy.hash(request == null ? null : request.getHeader("User-Agent"));

        List<EducationRecordAccessLogEntity> rows = new ArrayList<>();
        for (String subjectUserId : new LinkedHashSet<>(subjectUserIds == null ? List.of() : subjectUserIds)) {
            if (subjectUserId == null || subjectUserId.isBlank() || subjectUserId.equals(actorUserId)) {
                continue;
            }
            rows.add(new EducationRecordAccessLogEntity(actorUserId, subjectUserId, blankToNull(termId), accessType,
                    blankToNull(resourceType), blankToNull(resourceId), ipHash, userAgentHash, occurredAt, retainUntil));
        }
        if (!rows.isEmpty()) {
            repository.saveAll(rows);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
