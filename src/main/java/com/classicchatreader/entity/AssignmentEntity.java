package com.classicchatreader.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "assignments")
public class AssignmentEntity {

    public static final String QUIZ_SOURCE_CHAPTER = "CHAPTER";
    public static final String QUIZ_SOURCE_CUSTOM = "CUSTOM";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "term_id", nullable = false)
    private String termId;

    @Column(nullable = false)
    private String title;

    @Column(name = "book_id", nullable = false)
    private String bookId;

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

    /** CHAPTER = live-link the single-chapter default quiz; CUSTOM = assignment-owned payload. */
    @Column(name = "quiz_source", length = 16)
    private String quizSource;

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

    @OneToMany(mappedBy = "assignment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, chapterIndex ASC")
    private List<AssignmentChapterEntity> chapters = new ArrayList<>();

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

    public boolean isWholeBook() {
        return chapters == null || chapters.isEmpty();
    }

    public AssignmentChapterEntity firstChapter() {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        return chapters.stream()
                .min(Comparator
                        .comparingInt(AssignmentChapterEntity::getSortOrder)
                        .thenComparingInt(AssignmentChapterEntity::getChapterIndex))
                .orElse(chapters.get(0));
    }

    public String singleChapterId() {
        if (chapters == null || chapters.size() != 1) {
            return null;
        }
        return chapters.get(0).getChapterId();
    }

    public void replaceChapters(List<AssignmentChapterEntity> next) {
        if (chapters == null) {
            chapters = new ArrayList<>();
        }
        List<AssignmentChapterEntity> incoming = next == null ? List.of() : next;
        // Keep existing instances in the collection so orphanRemoval does not delete
        // a row that is about to be re-inserted under the same unique chapter key.
        Set<AssignmentChapterEntity> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        keep.addAll(incoming);
        chapters.removeIf(existing -> !keep.contains(existing));
        for (AssignmentChapterEntity chapter : incoming) {
            chapter.setAssignment(this);
            if (!chapters.contains(chapter)) {
                chapters.add(chapter);
            }
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTermId() { return termId; }
    public void setTermId(String termId) { this.termId = termId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
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
    public String getQuizSource() { return quizSource; }
    public void setQuizSource(String quizSource) { this.quizSource = quizSource; }
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
    public List<AssignmentChapterEntity> getChapters() { return chapters; }
    public void setChapters(List<AssignmentChapterEntity> chapters) { this.chapters = chapters; }
}
