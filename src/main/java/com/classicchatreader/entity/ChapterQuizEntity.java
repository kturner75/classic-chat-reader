package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "chapter_quizzes")
public class ChapterQuizEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id", nullable = false, unique = true)
    private ChapterEntity chapter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChapterQuizStatus status;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime generatedAt;

    @Column(length = 100)
    private String promptVersion;

    @Column(length = 200)
    private String modelName;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    public ChapterQuizEntity() {
    }

    public ChapterQuizEntity(ChapterEntity chapter) {
        this.chapter = chapter;
        this.status = ChapterQuizStatus.PENDING;
    }

    @PrePersist
    @PreUpdate
    public void updateTimestamps() {
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = ChapterQuizStatus.PENDING;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ChapterEntity getChapter() {
        return chapter;
    }

    public void setChapter(ChapterEntity chapter) {
        this.chapter = chapter;
    }

    public ChapterQuizStatus getStatus() {
        return status;
    }

    public void setStatus(ChapterQuizStatus status) {
        this.status = status;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(LocalDateTime generatedAt) {
        this.generatedAt = generatedAt;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }
}
