package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(
        name = "reading_buddy_memories",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_rbmem_owner_book_persona",
                        columnNames = {"owner_key", "book_id", "persona_id"}
                )
        }
)
public class ReadingBuddyMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "owner_key", nullable = false, length = 120)
    private String ownerKey;

    @Column(name = "book_id", nullable = false)
    private String bookId;

    @Column(name = "persona_id", nullable = false, length = 64)
    private String personaId;

    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText = "";

    @Column(name = "summary_version", nullable = false)
    private int summaryVersion = 0;

    @Column(name = "summary_max_chapter_index")
    private Integer summaryMaxChapterIndex;

    @Column(name = "summary_max_paragraph_index")
    private Integer summaryMaxParagraphIndex;

    @Column(name = "last_message_id", length = 255)
    private String lastMessageId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * When false, {@link #setUpdatedAt} stamped an explicit value (claim LWW)
     * and {@link #onUpdate} must not overwrite it.
     */
    @Transient
    private boolean autoTouchUpdatedAt = true;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
        if (summaryText == null) {
            summaryText = "";
        }
        autoTouchUpdatedAt = true;
    }

    @PreUpdate
    void onUpdate() {
        if (autoTouchUpdatedAt) {
            updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
        autoTouchUpdatedAt = true;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerKey() {
        return ownerKey;
    }

    public void setOwnerKey(String ownerKey) {
        this.ownerKey = ownerKey;
    }

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId;
    }

    public String getPersonaId() {
        return personaId;
    }

    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }

    public int getSummaryVersion() {
        return summaryVersion;
    }

    public void setSummaryVersion(int summaryVersion) {
        this.summaryVersion = summaryVersion;
    }

    public Integer getSummaryMaxChapterIndex() {
        return summaryMaxChapterIndex;
    }

    public void setSummaryMaxChapterIndex(Integer summaryMaxChapterIndex) {
        this.summaryMaxChapterIndex = summaryMaxChapterIndex;
    }

    public Integer getSummaryMaxParagraphIndex() {
        return summaryMaxParagraphIndex;
    }

    public void setSummaryMaxParagraphIndex(Integer summaryMaxParagraphIndex) {
        this.summaryMaxParagraphIndex = summaryMaxParagraphIndex;
    }

    public String getLastMessageId() {
        return lastMessageId;
    }

    public void setLastMessageId(String lastMessageId) {
        this.lastMessageId = lastMessageId;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
        // Explicit stamp (claim LWW) — do not let @PreUpdate clobber it.
        this.autoTouchUpdatedAt = false;
    }
}
