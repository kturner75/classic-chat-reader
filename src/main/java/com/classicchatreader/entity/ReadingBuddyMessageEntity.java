package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

@Entity
@Table(
        name = "reading_buddy_messages",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_rbm_proactive_position",
                        columnNames = {"owner_key", "book_id", "persona_id", "proactive_position_key"}
                )
        }
)
public class ReadingBuddyMessageEntity {

    private static final AtomicLong LAST_CHRONOLOGY_SEQUENCE = new AtomicLong();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "owner_key", nullable = false, length = 120)
    private String ownerKey;

    @Column(name = "book_id", nullable = false)
    private String bookId;

    @Column(name = "persona_id", nullable = false, length = 64)
    private String personaId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(name = "chapter_index", nullable = false)
    private int chapterIndex;

    @Column(name = "paragraph_index", nullable = false)
    private int paragraphIndex;

    /**
     * Set only for kind='proactive': '{chapterIndex}:{paragraphIndex}'.
     * NULL for chat/other so multiple chat rows do not collide on the unique index.
     */
    @Column(name = "proactive_position_key", length = 64)
    private String proactivePositionKey;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Application-monotonic insertion key used to break database timestamp ties.
     * The wall-clock component keeps values increasing across ordinary process restarts;
     * the atomic increment preserves order for multiple messages created in one millisecond.
     */
    @Column(name = "chronology_sequence", nullable = false)
    private long chronologySequence;

    /**
     * SHA-256 lowercase hex of UTF-8 {@code role + "\n" + kind + "\n" + content}.
     * No trailing newline after content.
     */
    public static String computeContentHash(String role, String kind, String content) {
        String safeRole = role == null ? "" : role;
        String safeKind = kind == null ? "" : kind;
        String safeContent = content == null ? "" : content;
        String payload = safeRole + "\n" + safeKind + "\n" + safeContent;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String proactivePositionKey(int chapterIndex, int paragraphIndex) {
        return chapterIndex + ":" + paragraphIndex;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (contentHash == null || contentHash.isBlank()) {
            contentHash = computeContentHash(role, kind, content);
        }
        if (chronologySequence <= 0) {
            chronologySequence = nextChronologySequence();
        }
    }

    public static long nextChronologySequence() {
        long wallClockFloor = System.currentTimeMillis() * 1_000_000L;
        return LAST_CHRONOLOGY_SEQUENCE.updateAndGet(previous ->
                Math.max(previous + 1, wallClockFloor));
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public int getChapterIndex() {
        return chapterIndex;
    }

    public void setChapterIndex(int chapterIndex) {
        this.chapterIndex = chapterIndex;
    }

    public int getParagraphIndex() {
        return paragraphIndex;
    }

    public void setParagraphIndex(int paragraphIndex) {
        this.paragraphIndex = paragraphIndex;
    }

    public String getProactivePositionKey() {
        return proactivePositionKey;
    }

    public void setProactivePositionKey(String proactivePositionKey) {
        this.proactivePositionKey = proactivePositionKey;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public long getChronologySequence() {
        return chronologySequence;
    }

    public void setChronologySequence(long chronologySequence) {
        this.chronologySequence = chronologySequence;
    }
}
