package com.classicchatreader.repository;

import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReadingBuddyMessageRepository extends JpaRepository<ReadingBuddyMessageEntity, String> {

    List<ReadingBuddyMessageEntity> findByOwnerKey(String ownerKey);

    List<ReadingBuddyMessageEntity> findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(
            String ownerKey,
            String bookId,
            String personaId
    );

    /**
     * Newest-first page for a thread (use with {@link Pageable} limit; reverse for chrono ASC).
     */
    List<ReadingBuddyMessageEntity> findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtDesc(
            String ownerKey,
            String bookId,
            String personaId,
            Pageable pageable
    );

    /**
     * Newest-first messages at or before the reader's position (spoiler-safe).
     * Lexicographic: chapter first, then paragraph.
     */
    @Query("""
            SELECT m FROM ReadingBuddyMessageEntity m
            WHERE m.ownerKey = :ownerKey
              AND m.bookId = :bookId
              AND m.personaId = :personaId
              AND (m.chapterIndex < :readerChapterIndex
                   OR (m.chapterIndex = :readerChapterIndex
                       AND m.paragraphIndex <= :readerParagraphIndex))
            ORDER BY m.createdAt DESC
            """)
    List<ReadingBuddyMessageEntity> findVisibleAtOrBeforeOrderByCreatedAtDesc(
            @Param("ownerKey") String ownerKey,
            @Param("bookId") String bookId,
            @Param("personaId") String personaId,
            @Param("readerChapterIndex") int readerChapterIndex,
            @Param("readerParagraphIndex") int readerParagraphIndex,
            Pageable pageable
    );

    long countByOwnerKeyAndBookIdAndPersonaId(String ownerKey, String bookId, String personaId);

    boolean existsByOwnerKeyAndBookIdAndPersonaIdAndContentHash(
            String ownerKey,
            String bookId,
            String personaId,
            String contentHash
    );

    Optional<ReadingBuddyMessageEntity> findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
            String ownerKey,
            String bookId,
            String personaId,
            String proactivePositionKey
    );

    /**
     * Most recent proactive comment for the thread (newest first with {@link Pageable} limit 1).
     */
    List<ReadingBuddyMessageEntity> findByOwnerKeyAndBookIdAndPersonaIdAndKindOrderByCreatedAtDesc(
            String ownerKey,
            String bookId,
            String personaId,
            String kind,
            Pageable pageable
    );

    /**
     * Most recent user chat turn for the thread (newest first with {@link Pageable} limit 1).
     */
    List<ReadingBuddyMessageEntity> findByOwnerKeyAndBookIdAndPersonaIdAndRoleAndKindOrderByCreatedAtDesc(
            String ownerKey,
            String bookId,
            String personaId,
            String role,
            String kind,
            Pageable pageable
    );

    long countByOwnerKeyAndBookIdAndPersonaIdAndKindAndChapterIndex(
            String ownerKey,
            String bookId,
            String personaId,
            String kind,
            int chapterIndex
    );

    long countByOwnerKeyAndBookIdAndPersonaIdAndKindAndCreatedAtGreaterThanEqual(
            String ownerKey,
            String bookId,
            String personaId,
            String kind,
            LocalDateTime createdAt
    );

    /**
     * Oldest proactive comment within the recent window (for hourly rate-cap retry timing).
     */
    @Query("""
            SELECT m FROM ReadingBuddyMessageEntity m
            WHERE m.ownerKey = :ownerKey
              AND m.bookId = :bookId
              AND m.personaId = :personaId
              AND m.kind = :kind
              AND m.createdAt >= :since
            ORDER BY m.createdAt ASC
            """)
    List<ReadingBuddyMessageEntity> findOldestSince(
            @Param("ownerKey") String ownerKey,
            @Param("bookId") String bookId,
            @Param("personaId") String personaId,
            @Param("kind") String kind,
            @Param("since") LocalDateTime since,
            Pageable pageable
    );

    void deleteByOwnerKeyAndBookIdAndPersonaId(String ownerKey, String bookId, String personaId);
}
