package com.classicchatreader.repository;

import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CharacterRepository extends JpaRepository<CharacterEntity, String> {

    @Query("SELECT c FROM CharacterEntity c JOIN FETCH c.book JOIN FETCH c.firstChapter WHERE c.id = :id")
    Optional<CharacterEntity> findByIdWithBookAndChapter(@Param("id") String id);

    List<CharacterEntity> findByBookIdOrderByCreatedAt(String bookId);

    Optional<CharacterEntity> findByBookIdAndNameIgnoreCase(String bookId, String name);

    List<CharacterEntity> findByBookIdAndStatus(String bookId, CharacterStatus status);

    List<CharacterEntity> findByBookIdAndFirstChapterIdOrderByFirstParagraphIndex(String bookId, String firstChapterId);

    List<CharacterEntity> findByStatus(CharacterStatus status);

    long countByStatus(CharacterStatus status);

    @Query("""
            SELECT COUNT(c)
            FROM CharacterEntity c
            WHERE c.book.id = :bookId
              AND c.status = :status
            """)
    long countByBookAndStatus(
            @Param("bookId") String bookId,
            @Param("status") CharacterStatus status);

    long countByBookIdAndCharacterType(String bookId, CharacterType characterType);

    @Query("SELECT c FROM CharacterEntity c WHERE c.book.id = :bookId " +
           "AND c.firstChapter.chapterIndex <= :chapterIndex " +
           "ORDER BY c.firstChapter.chapterIndex, c.firstParagraphIndex")
    List<CharacterEntity> findByBookIdUpToChapter(
            @Param("bookId") String bookId,
            @Param("chapterIndex") int chapterIndex);

    @Query("SELECT c FROM CharacterEntity c WHERE c.book.id = :bookId AND " +
           "(c.firstChapter.chapterIndex < :chapterIndex OR " +
           "(c.firstChapter.chapterIndex = :chapterIndex AND c.firstParagraphIndex <= :paragraphIndex)) " +
           "ORDER BY c.characterType ASC, c.firstChapter.chapterIndex, c.firstParagraphIndex")
    List<CharacterEntity> findByBookIdUpToPosition(
            @Param("bookId") String bookId,
            @Param("chapterIndex") int chapterIndex,
            @Param("paragraphIndex") int paragraphIndex);

    @Query("SELECT c FROM CharacterEntity c WHERE c.book.id = :bookId " +
           "AND c.status = 'COMPLETED' " +
           "AND c.completedAt > :sinceTime " +
           "ORDER BY c.completedAt")
    List<CharacterEntity> findNewlyCompletedSince(
            @Param("bookId") String bookId,
            @Param("sinceTime") LocalDateTime sinceTime);

    @Query("""
            SELECT COUNT(c)
            FROM CharacterEntity c
            WHERE c.status = :pendingStatus
              AND c.nextRetryAt IS NOT NULL
              AND c.nextRetryAt > :now
            """)
    long countScheduledRetries(
            @Param("pendingStatus") CharacterStatus pendingStatus,
            @Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(c)
            FROM CharacterEntity c
            WHERE c.book.id = :bookId
              AND c.status = :pendingStatus
              AND c.nextRetryAt IS NOT NULL
              AND c.nextRetryAt > :now
            """)
    long countScheduledRetriesForBook(
            @Param("bookId") String bookId,
            @Param("pendingStatus") CharacterStatus pendingStatus,
            @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE CharacterEntity c
            SET c.status = :generatingStatus,
                c.leaseOwner = :leaseOwner,
                c.leaseExpiresAt = :leaseExpiresAt,
                c.nextRetryAt = NULL
            WHERE c.id = :characterId
              AND (
                (c.status = :pendingStatus AND (c.nextRetryAt IS NULL OR c.nextRetryAt <= :now))
                OR (c.status = :generatingStatus AND (c.leaseExpiresAt IS NULL OR c.leaseExpiresAt < :now))
              )
            """)
    int claimPortraitLease(
            @Param("characterId") String characterId,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("leaseOwner") String leaseOwner,
            @Param("pendingStatus") CharacterStatus pendingStatus,
            @Param("generatingStatus") CharacterStatus generatingStatus);

    void deleteByBookId(String bookId);
}
