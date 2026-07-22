package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(
        name = "character_chat_conversations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ccc_id_user",
                columnNames = {"id", "user_id"}
        ),
        indexes = {
                @Index(
                        name = "idx_ccc_user_character_activity",
                        columnList = "user_id, character_id, updated_at, created_at"
                ),
                @Index(name = "idx_ccc_user_updated", columnList = "user_id, updated_at")
        }
)
public class CharacterChatConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "character_id", nullable = false)
    private String characterId;

    @Column(name = "context_chapter_id")
    private String contextChapterId;

    @Column(name = "context_chapter_index")
    private Integer contextChapterIndex;

    @Column(name = "context_chapter_title")
    private String contextChapterTitle;

    @Column(name = "context_paragraph_index")
    private Integer contextParagraphIndex;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCharacterId() {
        return characterId;
    }

    public void setCharacterId(String characterId) {
        this.characterId = characterId;
    }

    public String getContextChapterId() {
        return contextChapterId;
    }

    public void setContextChapterId(String contextChapterId) {
        this.contextChapterId = contextChapterId;
    }

    public Integer getContextChapterIndex() {
        return contextChapterIndex;
    }

    public void setContextChapterIndex(Integer contextChapterIndex) {
        this.contextChapterIndex = contextChapterIndex;
    }

    public String getContextChapterTitle() {
        return contextChapterTitle;
    }

    public void setContextChapterTitle(String contextChapterTitle) {
        this.contextChapterTitle = contextChapterTitle;
    }

    public Integer getContextParagraphIndex() {
        return contextParagraphIndex;
    }

    public void setContextParagraphIndex(Integer contextParagraphIndex) {
        this.contextParagraphIndex = contextParagraphIndex;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
