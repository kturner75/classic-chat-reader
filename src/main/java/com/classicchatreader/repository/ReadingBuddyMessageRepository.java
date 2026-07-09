package com.classicchatreader.repository;

import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    void deleteByOwnerKeyAndBookIdAndPersonaId(String ownerKey, String bookId, String personaId);
}
