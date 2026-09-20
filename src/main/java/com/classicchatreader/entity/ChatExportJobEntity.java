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
 * Record of one chat export (BL-043.7). v1 exports are generated synchronously and streamed
 * to the requester, so no artifact is stored ({@code artifact_storage_key} stays null) and the
 * row is written READY. The row is the durable "who exported what, when" record.
 */
@Entity
@Table(name = "chat_export_jobs")
public class ChatExportJobEntity {

    public static final String STATUS_READY = "READY";
    public static final String SOURCE_READING_BUDDY = "READING_BUDDY";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "requester_user_id", nullable = false)
    private String requesterUserId;

    @Column(name = "subject_user_id", nullable = false)
    private String subjectUserId;

    @Column(name = "term_id")
    private String termId;

    @Column(nullable = false, length = 16)
    private String format;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "chat_sources", nullable = false, length = 128)
    private String chatSources;

    @Column(name = "filter_book_id")
    private String filterBookId;

    @Column(name = "filter_from")
    private LocalDateTime filterFrom;

    @Column(name = "filter_to")
    private LocalDateTime filterTo;

    @Column(name = "artifact_storage_key", length = 512)
    private String artifactStorageKey;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected ChatExportJobEntity() {
    }

    public ChatExportJobEntity(String requesterUserId, String subjectUserId, String termId, String format,
                               String chatSources, LocalDateTime filterFrom, LocalDateTime filterTo, int retainDays) {
        this.requesterUserId = requesterUserId;
        this.subjectUserId = subjectUserId;
        this.termId = termId;
        this.format = format;
        this.chatSources = chatSources;
        this.filterFrom = filterFrom;
        this.filterTo = filterTo;
        this.status = STATUS_READY;
        // When this record itself may be purged (BL-043.6 part 3). v1 stores no artifact, so this is
        // the retention horizon for the record, not an artifact download deadline.
        this.expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(retainDays);
    }

    @PrePersist
    void defaults() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (completedAt == null && STATUS_READY.equals(status)) {
            completedAt = now;
        }
    }

    public String getId() { return id; }
    public String getRequesterUserId() { return requesterUserId; }
    public String getSubjectUserId() { return subjectUserId; }
    public String getTermId() { return termId; }
    public String getFormat() { return format; }
    public String getStatus() { return status; }
    public String getChatSources() { return chatSources; }
    public String getFilterBookId() { return filterBookId; }
    public LocalDateTime getFilterFrom() { return filterFrom; }
    public LocalDateTime getFilterTo() { return filterTo; }
    public String getArtifactStorageKey() { return artifactStorageKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
