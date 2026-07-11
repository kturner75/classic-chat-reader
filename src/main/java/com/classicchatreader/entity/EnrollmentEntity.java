package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(
        name = "enrollments",
        uniqueConstraints = @UniqueConstraint(name = "uk_enrollments_term_user", columnNames = {"term_id", "user_id"})
)
public class EnrollmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "term_id", nullable = false)
    private String termId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = 32)
    private String role = "STUDENT";

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "joined_date", nullable = false)
    private LocalDate joinedDate;

    @Column(name = "left_date")
    private LocalDate leftDate;

    @Column(name = "invite_link_id")
    private String inviteLinkId;

    @Column(name = "display_name_override")
    private String displayNameOverride;

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
        if (joinedDate == null) joinedDate = LocalDate.now(ZoneOffset.UTC);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTermId() { return termId; }
    public void setTermId(String termId) { this.termId = termId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getJoinedDate() { return joinedDate; }
    public void setJoinedDate(LocalDate joinedDate) { this.joinedDate = joinedDate; }
    public LocalDate getLeftDate() { return leftDate; }
    public void setLeftDate(LocalDate leftDate) { this.leftDate = leftDate; }
    public String getInviteLinkId() { return inviteLinkId; }
    public void setInviteLinkId(String inviteLinkId) { this.inviteLinkId = inviteLinkId; }
    public String getDisplayNameOverride() { return displayNameOverride; }
    public void setDisplayNameOverride(String displayNameOverride) { this.displayNameOverride = displayNameOverride; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
