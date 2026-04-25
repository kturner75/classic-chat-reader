package com.classicchatreader.repository;

import com.classicchatreader.entity.ChapterRecapEntity;
import com.classicchatreader.entity.ChapterRecapStatus;
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
public interface ChapterRecapRepository extends JpaRepository<ChapterRecapEntity, String> {

    Optional<ChapterRecapEntity> findByChapterId(String chapterId);

    List<ChapterRecapEntity> findByChapterBookIdAndStatus(String bookId, ChapterRecapStatus status);

    List<ChapterRecapEntity> findByChapterBookIdAndStatusIsNull(String bookId);

    boolean existsByChapterBookIdAndStatus(String bookId, ChapterRecapStatus status);

    List<ChapterRecapEntity> findByStatus(ChapterRecapStatus status);

    long countByStatus(ChapterRecapStatus status);

    long countByStatusIsNull();

    @Query("""
            SELECT COUNT(cr)
            FROM ChapterRecapEntity cr
            WHERE cr.chapter.book.id = :bookId
              AND cr.status = :status
            """)
    long countByBookAndStatus(
            @Param("bookId") String bookId,
            @Param("status") ChapterRecapStatus status);

    long countByChapterBookIdAndStatusIsNull(String bookId);

    @Query("""
            SELECT COUNT(cr)
            FROM ChapterRecapEntity cr
            WHERE cr.status = :pendingStatus
              AND cr.nextRetryAt IS NOT NULL
              AND cr.nextRetryAt > :now
            """)
    long countScheduledRetries(
            @Param("pendingStatus") ChapterRecapStatus pendingStatus,
            @Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(cr)
            FROM ChapterRecapEntity cr
            WHERE cr.chapter.book.id = :bookId
              AND cr.status = :pendingStatus
              AND cr.nextRetryAt IS NOT NULL
              AND cr.nextRetryAt > :now
            """)
    long countScheduledRetriesForBook(
            @Param("bookId") String bookId,
            @Param("pendingStatus") ChapterRecapStatus pendingStatus,
            @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ChapterRecapEntity cr
            SET cr.status = :generatingStatus,
                cr.updatedAt = :now,
                cr.leaseOwner = :leaseOwner,
                cr.leaseExpiresAt = :leaseExpiresAt,
                cr.nextRetryAt = NULL
            WHERE cr.chapter.id = :chapterId
              AND (
                (cr.status = :pendingStatus AND (cr.nextRetryAt IS NULL OR cr.nextRetryAt <= :now))
                OR (cr.status = :generatingStatus AND (cr.leaseExpiresAt IS NULL OR cr.leaseExpiresAt < :now))
              )
            """)
    int claimGenerationLease(
            @Param("chapterId") String chapterId,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("leaseOwner") String leaseOwner,
            @Param("pendingStatus") ChapterRecapStatus pendingStatus,
            @Param("generatingStatus") ChapterRecapStatus generatingStatus);

    @Query("SELECT cr FROM ChapterRecapEntity cr JOIN FETCH cr.chapter c JOIN FETCH c.book WHERE c.id = :chapterId")
    Optional<ChapterRecapEntity> findByChapterIdWithChapterAndBook(@Param("chapterId") String chapterId);

    @Modifying
    @Query("DELETE FROM ChapterRecapEntity cr WHERE cr.chapter.book.id = :bookId")
    void deleteByBookId(@Param("bookId") String bookId);
}
