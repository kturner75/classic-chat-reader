package com.classicchatreader.repository;

import com.classicchatreader.entity.ChapterAnalysisEntity;
import com.classicchatreader.entity.ChapterAnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

@Repository
public interface ChapterAnalysisRepository extends JpaRepository<ChapterAnalysisEntity, String> {

    Optional<ChapterAnalysisEntity> findByChapterId(String chapterId);

    boolean existsByChapterId(String chapterId);

    List<ChapterAnalysisEntity> findByChapterBookIdAndStatus(String bookId, ChapterAnalysisStatus status);

    List<ChapterAnalysisEntity> findByChapterBookIdAndStatusIsNull(String bookId);

    List<ChapterAnalysisEntity> findByStatus(ChapterAnalysisStatus status);

    long countByStatus(ChapterAnalysisStatus status);

    long countByStatusIsNull();

    @Query("""
            SELECT COUNT(ca)
            FROM ChapterAnalysisEntity ca
            WHERE ca.chapter.book.id = :bookId
              AND ca.status = :status
            """)
    long countByBookAndStatus(
            @Param("bookId") String bookId,
            @Param("status") ChapterAnalysisStatus status);

    long countByChapterBookIdAndStatusIsNull(String bookId);

    @Query("""
            SELECT COUNT(ca)
            FROM ChapterAnalysisEntity ca
            WHERE ca.status = :pendingStatus
              AND ca.nextRetryAt IS NOT NULL
              AND ca.nextRetryAt > :now
            """)
    long countScheduledRetries(
            @Param("pendingStatus") ChapterAnalysisStatus pendingStatus,
            @Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(ca)
            FROM ChapterAnalysisEntity ca
            WHERE ca.chapter.book.id = :bookId
              AND ca.status = :pendingStatus
              AND ca.nextRetryAt IS NOT NULL
              AND ca.nextRetryAt > :now
            """)
    long countScheduledRetriesForBook(
            @Param("bookId") String bookId,
            @Param("pendingStatus") ChapterAnalysisStatus pendingStatus,
            @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ChapterAnalysisEntity ca
            SET ca.status = :generatingStatus,
                ca.leaseOwner = :leaseOwner,
                ca.leaseExpiresAt = :leaseExpiresAt,
                ca.nextRetryAt = NULL
            WHERE ca.chapter.id = :chapterId
              AND (
                ((ca.status = :pendingStatus OR ca.status IS NULL)
                    AND (ca.nextRetryAt IS NULL OR ca.nextRetryAt <= :now))
                OR (ca.status = :generatingStatus AND (ca.leaseExpiresAt IS NULL OR ca.leaseExpiresAt < :now))
              )
            """)
    int claimAnalysisLease(
            @Param("chapterId") String chapterId,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("leaseOwner") String leaseOwner,
            @Param("pendingStatus") ChapterAnalysisStatus pendingStatus,
            @Param("generatingStatus") ChapterAnalysisStatus generatingStatus);

    @Modifying
    @Query("DELETE FROM ChapterAnalysisEntity ca WHERE ca.chapter.book.id = :bookId")
    void deleteByBookId(@Param("bookId") String bookId);
}
