package com.classicchatreader.repository;

import com.classicchatreader.entity.IllustrationEntity;
import com.classicchatreader.entity.IllustrationStatus;
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
public interface IllustrationRepository extends JpaRepository<IllustrationEntity, String> {

    Optional<IllustrationEntity> findByChapterId(String chapterId);

    List<IllustrationEntity> findByStatus(IllustrationStatus status);

    List<IllustrationEntity> findByChapterBookIdAndStatus(String bookId, IllustrationStatus status);

    long countByStatus(IllustrationStatus status);

    @Query("""
            SELECT COUNT(i)
            FROM IllustrationEntity i
            WHERE i.chapter.book.id = :bookId
              AND i.status = :status
            """)
    long countByBookAndStatus(
            @Param("bookId") String bookId,
            @Param("status") IllustrationStatus status);

    @Query("""
            SELECT COUNT(i)
            FROM IllustrationEntity i
            WHERE i.status = :pendingStatus
              AND i.nextRetryAt IS NOT NULL
              AND i.nextRetryAt > :now
            """)
    long countScheduledRetries(
            @Param("pendingStatus") IllustrationStatus pendingStatus,
            @Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(i)
            FROM IllustrationEntity i
            WHERE i.chapter.book.id = :bookId
              AND i.status = :pendingStatus
              AND i.nextRetryAt IS NOT NULL
              AND i.nextRetryAt > :now
            """)
    long countScheduledRetriesForBook(
            @Param("bookId") String bookId,
            @Param("pendingStatus") IllustrationStatus pendingStatus,
            @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE IllustrationEntity i
            SET i.status = :generatingStatus,
                i.leaseOwner = :leaseOwner,
                i.leaseExpiresAt = :leaseExpiresAt,
                i.nextRetryAt = NULL
            WHERE i.chapter.id = :chapterId
              AND (
                (i.status = :pendingStatus AND (i.nextRetryAt IS NULL OR i.nextRetryAt <= :now))
                OR (i.status = :generatingStatus AND (i.leaseExpiresAt IS NULL OR i.leaseExpiresAt < :now))
              )
            """)
    int claimGenerationLease(
            @Param("chapterId") String chapterId,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("leaseOwner") String leaseOwner,
            @Param("pendingStatus") IllustrationStatus pendingStatus,
            @Param("generatingStatus") IllustrationStatus generatingStatus);

    @Modifying
    @Query("DELETE FROM IllustrationEntity i WHERE i.chapter.book.id = :bookId")
    void deleteByBookId(@Param("bookId") String bookId);
}
