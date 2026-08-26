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

    /**
     * Trusted roster for first-appearance refine. {@code firstChapter} is join-fetched
     * so the queue processor can compare chapter index after the repository session closes.
     */
    @Query("SELECT c FROM CharacterEntity c JOIN FETCH c.firstChapter WHERE c.book.id = :bookId ORDER BY c.createdAt")
    List<CharacterEntity> findByBookIdWithFirstChapterOrderByCreatedAt(@Param("bookId") String bookId);

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

    /**
     * Atomically reserves a custom-prompt regeneration. Returns 0 when another
     * writer already moved the row out of COMPLETED/FAILED.
     * Writes {@code directedMarker} into {@code portraitFilename} so recovery can
     * tell a directed job from an auto-generated prompt after the row leaves PENDING.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE CharacterEntity c
            SET c.status = :pendingStatus,
                c.portraitPrompt = :prompt,
                c.errorMessage = NULL,
                c.portraitFilename = :directedMarker,
                c.completedAt = NULL,
                c.retryCount = 0,
                c.nextRetryAt = NULL,
                c.leaseOwner = NULL,
                c.leaseExpiresAt = NULL
            WHERE c.id = :characterId
              AND (c.status = :completedStatus OR c.status = :failedStatus)
            """)
    int claimPortraitRegeneration(
            @Param("characterId") String characterId,
            @Param("prompt") String prompt,
            @Param("directedMarker") String directedMarker,
            @Param("pendingStatus") CharacterStatus pendingStatus,
            @Param("completedStatus") CharacterStatus completedStatus,
            @Param("failedStatus") CharacterStatus failedStatus);

    /**
     * Restores a cached portrait only when a directed regeneration has not claimed
     * the row. Returns 0 if {@code portraitFilename} is the directed marker.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE CharacterEntity c
            SET c.status = :completedStatus,
                c.portraitFilename = :filename,
                c.errorMessage = NULL,
                c.retryCount = 0,
                c.completedAt = :completedAt,
                c.nextRetryAt = NULL,
                c.leaseOwner = NULL,
                c.leaseExpiresAt = NULL
            WHERE c.id = :characterId
              AND (c.portraitFilename IS NULL OR c.portraitFilename <> :directedMarker)
            """)
    int claimCachedPortraitRestore(
            @Param("characterId") String characterId,
            @Param("filename") String filename,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("directedMarker") String directedMarker,
            @Param("completedStatus") CharacterStatus completedStatus);

    /**
     * Resets a failed auto-portrait back to PENDING. Returns 0 when a directed
     * regeneration already claimed the row.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE CharacterEntity c
            SET c.status = :pendingStatus,
                c.errorMessage = NULL,
                c.retryCount = 0,
                c.nextRetryAt = NULL,
                c.leaseOwner = NULL,
                c.leaseExpiresAt = NULL
            WHERE c.id = :characterId
              AND c.status = :failedStatus
              AND (c.portraitFilename IS NULL OR c.portraitFilename <> :directedMarker)
            """)
    int claimFailedAutoPortraitRetry(
            @Param("characterId") String characterId,
            @Param("failedStatus") CharacterStatus failedStatus,
            @Param("pendingStatus") CharacterStatus pendingStatus,
            @Param("directedMarker") String directedMarker);

    /**
     * Requeues a failed directed regeneration while keeping the marker and prompt.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE CharacterEntity c
            SET c.status = :pendingStatus,
                c.errorMessage = NULL,
                c.retryCount = 0,
                c.nextRetryAt = NULL,
                c.leaseOwner = NULL,
                c.leaseExpiresAt = NULL
            WHERE c.id = :characterId
              AND c.status = :failedStatus
              AND c.portraitFilename = :directedMarker
            """)
    int claimFailedDirectedPortraitRetry(
            @Param("characterId") String characterId,
            @Param("failedStatus") CharacterStatus failedStatus,
            @Param("pendingStatus") CharacterStatus pendingStatus,
            @Param("directedMarker") String directedMarker);

    /**
     * Requeues a COMPLETED row whose portrait file is gone. Returns 0 when a
     * directed regeneration already claimed the slot.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE CharacterEntity c
            SET c.status = :pendingStatus,
                c.portraitFilename = NULL,
                c.errorMessage = NULL,
                c.retryCount = 0,
                c.completedAt = NULL,
                c.nextRetryAt = NULL,
                c.leaseOwner = NULL,
                c.leaseExpiresAt = NULL
            WHERE c.id = :characterId
              AND c.status = :completedStatus
              AND (c.portraitFilename IS NULL OR c.portraitFilename <> :directedMarker)
            """)
    int claimMissingCompletedPortraitRetry(
            @Param("characterId") String characterId,
            @Param("completedStatus") CharacterStatus completedStatus,
            @Param("pendingStatus") CharacterStatus pendingStatus,
            @Param("directedMarker") String directedMarker);

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

    /**
     * Atomically records the call-voice assignment, but only if no assignment exists yet
     * for this provider. Returns 0 when a concurrent session already claimed it, so the
     * caller can adopt the persisted winner instead of racing.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE CharacterEntity c
            SET c.callVoice = :voice,
                c.callVoiceProvider = :provider
            WHERE c.id = :characterId
              AND (c.callVoice IS NULL OR c.callVoiceProvider IS NULL OR c.callVoiceProvider <> :provider)
            """)
    int claimCallVoice(
            @Param("characterId") String characterId,
            @Param("voice") String voice,
            @Param("provider") String provider);

    void deleteByBookId(String bookId);
}
