package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "quiz_question_overrides")
public class QuizQuestionOverrideEntity {

    public static final String OPERATION_ADD = "ADD";
    public static final String OPERATION_OVERRIDE = "OVERRIDE";
    public static final String OPERATION_DISABLE = "DISABLE";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "term_id", nullable = false)
    private String termId;

    @Column(name = "book_id", nullable = false)
    private String bookId;

    @Column(name = "chapter_id", nullable = false)
    private String chapterId;

    @Column(nullable = false, length = 16)
    private String operation;

    @Column(name = "source_question_id", length = 128)
    private String sourceQuestionId;

    @Column(name = "overlay_key", nullable = false, length = 160)
    private String overlayKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "question_json", columnDefinition = "TEXT")
    private String questionJson;

    @Column(nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Column(name = "base_prompt_version", length = 100)
    private String basePromptVersion;

    @Column(name = "created_by_user_id")
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(length = 500)
    private String notes;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTermId() { return termId; }
    public void setTermId(String termId) { this.termId = termId; }
    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getSourceQuestionId() { return sourceQuestionId; }
    public void setSourceQuestionId(String sourceQuestionId) { this.sourceQuestionId = sourceQuestionId; }
    public String getOverlayKey() { return overlayKey; }
    public void setOverlayKey(String overlayKey) { this.overlayKey = overlayKey; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getQuestionJson() { return questionJson; }
    public void setQuestionJson(String questionJson) { this.questionJson = questionJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBasePromptVersion() { return basePromptVersion; }
    public void setBasePromptVersion(String basePromptVersion) { this.basePromptVersion = basePromptVersion; }
    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
