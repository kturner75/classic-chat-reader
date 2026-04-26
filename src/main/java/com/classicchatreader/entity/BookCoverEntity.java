package com.classicchatreader.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "book_covers")
public class BookCoverEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false, unique = true)
    private BookEntity book;

    private String imageFilename;

    @Column(length = 2000)
    private String generatedPrompt;

    @Column(length = 2000)
    private String promptOverride;

    @Column(length = 64)
    private String coverSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IllustrationStatus status;

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

    public BookCoverEntity() {}

    public BookCoverEntity(BookEntity book) {
        this.book = book;
        this.status = IllustrationStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.retryCount = 0;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public BookEntity getBook() { return book; }
    public void setBook(BookEntity book) { this.book = book; }

    public String getImageFilename() { return imageFilename; }
    public void setImageFilename(String imageFilename) { this.imageFilename = imageFilename; }

    public String getGeneratedPrompt() { return generatedPrompt; }
    public void setGeneratedPrompt(String generatedPrompt) { this.generatedPrompt = generatedPrompt; }

    public String getPromptOverride() { return promptOverride; }
    public void setPromptOverride(String promptOverride) { this.promptOverride = promptOverride; }

    public String getCoverSource() { return coverSource; }
    public void setCoverSource(String coverSource) { this.coverSource = coverSource; }

    public IllustrationStatus getStatus() { return status; }
    public void setStatus(IllustrationStatus status) { this.status = status; }

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
