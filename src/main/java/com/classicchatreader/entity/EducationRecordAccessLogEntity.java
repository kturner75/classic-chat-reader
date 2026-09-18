package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * FERPA access audit row (BL-043.5). Written whenever someone other than the subject
 * reads or exports that student's education records. Never soft-deleted with student
 * content; purge only after {@code retain_until}.
 */
@Entity
@Table(name = "education_record_access_logs")
public class EducationRecordAccessLogEntity {

    public static final String ACCESS_VIEW_ROSTER = "VIEW_ROSTER";
    public static final String ACCESS_VIEW_STUDENT_OVERVIEW = "VIEW_STUDENT_OVERVIEW";
    public static final String ACCESS_EXPORT_CHAT = "EXPORT_CHAT";

    public static final String RESOURCE_TERM = "TERM";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "actor_user_id", nullable = false)
    private String actorUserId;

    @Column(name = "subject_user_id", nullable = false)
    private String subjectUserId;

    @Column(name = "term_id")
    private String termId;

    @Column(name = "access_type", nullable = false, length = 64)
    private String accessType;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "ip_hash", length = 120)
    private String ipHash;

    @Column(name = "user_agent_hash", length = 120)
    private String userAgentHash;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "retain_until")
    private LocalDateTime retainUntil;

    protected EducationRecordAccessLogEntity() {
    }

    public EducationRecordAccessLogEntity(
            String actorUserId,
            String subjectUserId,
            String termId,
            String accessType,
            String resourceType,
            String resourceId,
            String ipHash,
            String userAgentHash,
            LocalDateTime occurredAt,
            LocalDateTime retainUntil) {
        this.actorUserId = actorUserId;
        this.subjectUserId = subjectUserId;
        this.termId = termId;
        this.accessType = accessType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.ipHash = ipHash;
        this.userAgentHash = userAgentHash;
        this.occurredAt = occurredAt;
        this.retainUntil = retainUntil;
    }

    @PrePersist
    void defaults() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public String getId() {
        return id;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public String getSubjectUserId() {
        return subjectUserId;
    }

    public String getTermId() {
        return termId;
    }

    public String getAccessType() {
        return accessType;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getIpHash() {
        return ipHash;
    }

    public String getUserAgentHash() {
        return userAgentHash;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public LocalDateTime getRetainUntil() {
        return retainUntil;
    }
}
