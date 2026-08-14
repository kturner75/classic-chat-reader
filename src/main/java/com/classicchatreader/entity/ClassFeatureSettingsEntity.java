package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "class_feature_settings")
public class ClassFeatureSettingsEntity {

    @Id
    @Column(name = "term_id")
    private String termId;

    @Column(name = "quiz_enabled", nullable = false)
    private boolean quizEnabled = true;

    @Column(name = "recap_enabled", nullable = false)
    private boolean recapEnabled = true;

    @Column(name = "tts_enabled", nullable = false)
    private boolean ttsEnabled = true;

    @Column(name = "illustration_enabled", nullable = false)
    private boolean illustrationEnabled = true;

    @Column(name = "character_enabled", nullable = false)
    private boolean characterEnabled = true;

    @Column(name = "chat_enabled", nullable = false)
    private boolean chatEnabled = true;

    @Column(name = "speed_reading_enabled", nullable = false)
    private boolean speedReadingEnabled = true;

    @Column(name = "reading_buddy_enabled", nullable = false)
    private boolean readingBuddyEnabled = true;

    @Column(name = "default_quiz_question_count", nullable = false)
    private int defaultQuizQuestionCount = 10;

    @Column(name = "default_quiz_pass_min_correct")
    private Integer defaultQuizPassMinCorrect;

    @Column(name = "default_quiz_max_retries")
    private Integer defaultQuizMaxRetries;

    @Column(name = "default_quiz_option_count", nullable = false)
    private int defaultQuizOptionCount = 4;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by_user_id")
    private String updatedByUserId;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public String getTermId() { return termId; }
    public void setTermId(String termId) { this.termId = termId; }
    public boolean isQuizEnabled() { return quizEnabled; }
    public void setQuizEnabled(boolean quizEnabled) { this.quizEnabled = quizEnabled; }
    public boolean isRecapEnabled() { return recapEnabled; }
    public void setRecapEnabled(boolean recapEnabled) { this.recapEnabled = recapEnabled; }
    public boolean isTtsEnabled() { return ttsEnabled; }
    public void setTtsEnabled(boolean ttsEnabled) { this.ttsEnabled = ttsEnabled; }
    public boolean isIllustrationEnabled() { return illustrationEnabled; }
    public void setIllustrationEnabled(boolean illustrationEnabled) { this.illustrationEnabled = illustrationEnabled; }
    public boolean isCharacterEnabled() { return characterEnabled; }
    public void setCharacterEnabled(boolean characterEnabled) { this.characterEnabled = characterEnabled; }
    public boolean isChatEnabled() { return chatEnabled; }
    public void setChatEnabled(boolean chatEnabled) { this.chatEnabled = chatEnabled; }
    public boolean isSpeedReadingEnabled() { return speedReadingEnabled; }
    public void setSpeedReadingEnabled(boolean speedReadingEnabled) { this.speedReadingEnabled = speedReadingEnabled; }
    public boolean isReadingBuddyEnabled() { return readingBuddyEnabled; }
    public void setReadingBuddyEnabled(boolean readingBuddyEnabled) { this.readingBuddyEnabled = readingBuddyEnabled; }
    public int getDefaultQuizQuestionCount() { return defaultQuizQuestionCount; }
    public void setDefaultQuizQuestionCount(int defaultQuizQuestionCount) {
        this.defaultQuizQuestionCount = defaultQuizQuestionCount;
    }
    public Integer getDefaultQuizPassMinCorrect() { return defaultQuizPassMinCorrect; }
    public void setDefaultQuizPassMinCorrect(Integer defaultQuizPassMinCorrect) {
        this.defaultQuizPassMinCorrect = defaultQuizPassMinCorrect;
    }
    public Integer getDefaultQuizMaxRetries() { return defaultQuizMaxRetries; }
    public void setDefaultQuizMaxRetries(Integer defaultQuizMaxRetries) {
        this.defaultQuizMaxRetries = defaultQuizMaxRetries;
    }
    public int getDefaultQuizOptionCount() { return defaultQuizOptionCount; }
    public void setDefaultQuizOptionCount(int defaultQuizOptionCount) {
        this.defaultQuizOptionCount = defaultQuizOptionCount;
    }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(String updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
