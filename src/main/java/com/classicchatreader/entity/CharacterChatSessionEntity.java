package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(
        name = "character_chat_sessions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_character_chat_owner_book_character",
                columnNames = {"owner_user_id", "book_id", "character_id"}
        )
)
public class CharacterChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "owner_user_id", nullable = false)
    private String ownerUserId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private BookEntity book;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    private CharacterEntity character;

    @Column(name = "book_title_snapshot", nullable = false)
    private String bookTitleSnapshot;

    @Column(name = "book_author_snapshot", nullable = false)
    private String bookAuthorSnapshot;

    @Column(name = "character_name_snapshot", nullable = false)
    private String characterNameSnapshot;

    @Column(name = "portrait_available_snapshot", nullable = false)
    private boolean portraitAvailableSnapshot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "context_chapter_id", nullable = false)
    private ChapterEntity contextChapter;

    @Column(name = "context_chapter_index", nullable = false)
    private int contextChapterIndex;

    @Column(name = "context_chapter_title", nullable = false)
    private String contextChapterTitle;

    @Column(name = "context_paragraph_index", nullable = false)
    private int contextParagraphIndex;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_message_at", nullable = false)
    private LocalDateTime lastMessageAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (lastMessageAt == null) lastMessageAt = createdAt;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public BookEntity getBook() { return book; }
    public void setBook(BookEntity book) { this.book = book; }
    public CharacterEntity getCharacter() { return character; }
    public void setCharacter(CharacterEntity character) { this.character = character; }
    public String getBookTitleSnapshot() { return bookTitleSnapshot; }
    public void setBookTitleSnapshot(String bookTitleSnapshot) { this.bookTitleSnapshot = bookTitleSnapshot; }
    public String getBookAuthorSnapshot() { return bookAuthorSnapshot; }
    public void setBookAuthorSnapshot(String bookAuthorSnapshot) { this.bookAuthorSnapshot = bookAuthorSnapshot; }
    public String getCharacterNameSnapshot() { return characterNameSnapshot; }
    public void setCharacterNameSnapshot(String characterNameSnapshot) { this.characterNameSnapshot = characterNameSnapshot; }
    public boolean isPortraitAvailableSnapshot() { return portraitAvailableSnapshot; }
    public void setPortraitAvailableSnapshot(boolean portraitAvailableSnapshot) { this.portraitAvailableSnapshot = portraitAvailableSnapshot; }
    public ChapterEntity getContextChapter() { return contextChapter; }
    public void setContextChapter(ChapterEntity contextChapter) { this.contextChapter = contextChapter; }
    public int getContextChapterIndex() { return contextChapterIndex; }
    public void setContextChapterIndex(int contextChapterIndex) { this.contextChapterIndex = contextChapterIndex; }
    public String getContextChapterTitle() { return contextChapterTitle; }
    public void setContextChapterTitle(String contextChapterTitle) { this.contextChapterTitle = contextChapterTitle; }
    public int getContextParagraphIndex() { return contextParagraphIndex; }
    public void setContextParagraphIndex(int contextParagraphIndex) { this.contextParagraphIndex = contextParagraphIndex; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(LocalDateTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
