package com.classicchatreader.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "characters", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"book_id", "name"})
})
public class CharacterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private BookEntity book;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "first_chapter_id", nullable = false)
    private ChapterEntity firstChapter;

    @Column(nullable = false)
    private int firstParagraphIndex;

    private String portraitFilename;

    @Column(length = 2000)
    private String portraitPrompt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CharacterStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CharacterType characterType = CharacterType.SECONDARY;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(length = 120)
    private String leaseOwner;

    private LocalDateTime leaseExpiresAt;

    @Column(nullable = false)
    private int retryCount;

    private LocalDateTime nextRetryAt;

    private LocalDateTime completedAt;

    public CharacterEntity() {}

    public CharacterEntity(BookEntity book, String name, String description,
                          ChapterEntity firstChapter, int firstParagraphIndex) {
        this.book = book;
        this.name = name;
        this.description = description;
        this.firstChapter = firstChapter;
        this.firstParagraphIndex = firstParagraphIndex;
        this.status = CharacterStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.retryCount = 0;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public BookEntity getBook() { return book; }
    public void setBook(BookEntity book) { this.book = book; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ChapterEntity getFirstChapter() { return firstChapter; }
    public void setFirstChapter(ChapterEntity firstChapter) { this.firstChapter = firstChapter; }

    public int getFirstParagraphIndex() { return firstParagraphIndex; }
    public void setFirstParagraphIndex(int firstParagraphIndex) { this.firstParagraphIndex = firstParagraphIndex; }

    public String getPortraitFilename() { return portraitFilename; }
    public void setPortraitFilename(String portraitFilename) { this.portraitFilename = portraitFilename; }

    public String getPortraitPrompt() { return portraitPrompt; }
    public void setPortraitPrompt(String portraitPrompt) { this.portraitPrompt = portraitPrompt; }

    public CharacterStatus getStatus() { return status; }
    public void setStatus(CharacterStatus status) { this.status = status; }

    public CharacterType getCharacterType() { return characterType; }
    public void setCharacterType(CharacterType characterType) { this.characterType = characterType; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }

    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
