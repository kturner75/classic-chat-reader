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
        name = "reading_buddy_preferences",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_rbp_owner_book",
                        columnNames = {"owner_key", "book_id"}
                )
        }
)
public class ReadingBuddyPreferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "owner_key", nullable = false, length = 120)
    private String ownerKey;

    /**
     * Real book id, or {@code __global__} for the single global prefs row per owner.
     * No FK — global sentinel is not a books row.
     */
    @Column(name = "book_id", nullable = false)
    private String bookId;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(nullable = false, length = 32)
    private String frequency = "rare";

    @Column(name = "default_persona_id", length = 64)
    private String defaultPersonaId;

    @Column(name = "persona_id", length = 64)
    private String personaId;

    @Column(name = "suppress_until")
    private LocalDateTime suppressUntil;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * When false, {@link #setUpdatedAt} stamped an explicit value (claim LWW or service touch)
     * and {@link #onUpdate} must not overwrite it.
     */
    @Transient
    private boolean autoTouchUpdatedAt = true;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public String getDefaultPersonaId() {
        return defaultPersonaId;
    }

    public void setDefaultPersonaId(String defaultPersonaId) {
        this.defaultPersonaId = defaultPersonaId;
    }

    public String getPersonaId() {
        return personaId;
    }

    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    public LocalDateTime getSuppressUntil() {
        return suppressUntil;
    }

    public void setSuppressUntil(LocalDateTime suppressUntil) {
        this.suppressUntil = suppressUntil;
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
        // Explicit stamp (claim LWW / service touch) — do not let @PreUpdate clobber it.
        this.autoTouchUpdatedAt = false;
    }
}
