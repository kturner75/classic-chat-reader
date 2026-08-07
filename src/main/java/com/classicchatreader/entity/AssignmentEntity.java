package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "assignments")
public class AssignmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "term_id", nullable = false)
    private String termId;

    @Column(nullable = false)
    private String title;

    @Column(name = "book_id", nullable = false)
    private String bookId;

    @Column(name = "chapter_id")
    private String chapterId;

    @Column(name = "chapter_index")
    private Integer chapterIndex;

    /** Calendar day only (SQL DATE); not an instant. */
    @Column(name = "due_date", columnDefinition = "DATE")
    private LocalDate dueDate;

    /** Inclusive open calendar day (SQL DATE); not an instant. */
    @Column(name = "available_from_date", columnDefinition = "DATE")
    private LocalDate availableFromDate;

    @Column(name = "quiz_required", nullable = false)
    private boolean quizRequired = false;

    @Column(name = "character_chat_required", nullable = false)
    private boolean characterChatRequired = false;

    /** Minimum correct answers required to pass when quiz is required; null = any attempt completes. */
    @Column(name = "quiz_pass_min_correct")
    private Integer quizPassMinCorrect;

    /** Extra attempts after the first when a pass minimum is set; 0 = initial attempt only. */
    @Column(name = "quiz_max_retries")
    private Integer quizMaxRetries;

    /** When pass rules became active for attempt-window scoping (UTC). */
    @Column(name = "quiz_rules_activated_at")
    private LocalDateTime quizRulesActivatedAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false, length = 32)
    private String status = "DRAFT";

    @Column(name = "created_by_user_id")
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTermId() { return termId; }
    public void setTermId(String termId) { this.termId = termId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public Integer getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(Integer chapterIndex) { this.chapterIndex = chapterIndex; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public LocalDate getAvailableFromDate() { return availableFromDate; }
    public void setAvailableFromDate(LocalDate availableFromDate) { this.availableFromDate = availableFromDate; }
    public boolean isQuizRequired() { return quizRequired; }
    public void setQuizRequired(boolean quizRequired) { this.quizRequired = quizRequired; }
    public boolean isCharacterChatRequired() { return characterChatRequired; }
    public void setCharacterChatRequired(boolean characterChatRequired) {
        this.characterChatRequired = characterChatRequired;
    }
    public Integer getQuizPassMinCorrect() { return quizPassMinCorrect; }
    public void setQuizPassMinCorrect(Integer quizPassMinCorrect) { this.quizPassMinCorrect = quizPassMinCorrect; }
    public Integer getQuizMaxRetries() { return quizMaxRetries; }
    public void setQuizMaxRetries(Integer quizMaxRetries) { this.quizMaxRetries = quizMaxRetries; }
    public LocalDateTime getQuizRulesActivatedAt() { return quizRulesActivatedAt; }
    public void setQuizRulesActivatedAt(LocalDateTime quizRulesActivatedAt) {
        this.quizRulesActivatedAt = quizRulesActivatedAt;
    }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
