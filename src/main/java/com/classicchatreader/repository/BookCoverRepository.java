package com.classicchatreader.repository;

import com.classicchatreader.entity.BookCoverEntity;
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
public interface BookCoverRepository extends JpaRepository<BookCoverEntity, String> {

    Optional<BookCoverEntity> findByBookId(String bookId);

    List<BookCoverEntity> findByStatus(IllustrationStatus status);

    long countByStatus(IllustrationStatus status);

    @Query("""
            SELECT COUNT(c)
            FROM BookCoverEntity c
            WHERE c.book.id = :bookId
              AND c.status = :status
            """)
    long countByBookAndStatus(
            @Param("bookId") String bookId,
            @Param("status") IllustrationStatus status);

    @Query("""
            SELECT COUNT(c)
            FROM BookCoverEntity c
            WHERE c.status = :pendingStatus
              AND c.nextRetryAt IS NOT NULL
              AND c.nextRetryAt > :now
            """)
    long countScheduledRetries(
            @Param("pendingStatus") IllustrationStatus pendingStatus,
            @Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(c)
            FROM BookCoverEntity c
            WHERE c.book.id = :bookId
              AND c.status = :pendingStatus
              AND c.nextRetryAt IS NOT NULL
              AND c.nextRetryAt > :now
            """)
    long countScheduledRetriesForBook(
            @Param("bookId") String bookId,
            @Param("pendingStatus") IllustrationStatus pendingStatus,
            @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE BookCoverEntity c
            SET c.status = :generatingStatus,
                c.leaseOwner = :leaseOwner,
                c.leaseExpiresAt = :leaseExpiresAt,
                c.nextRetryAt = NULL
            WHERE c.book.id = :bookId
              AND (
                (c.status = :pendingStatus AND (c.nextRetryAt IS NULL OR c.nextRetryAt <= :now))
                OR (c.status = :generatingStatus AND (c.leaseExpiresAt IS NULL OR c.leaseExpiresAt < :now))
              )
            """)
    int claimGenerationLease(
            @Param("bookId") String bookId,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("leaseOwner") String leaseOwner,
            @Param("pendingStatus") IllustrationStatus pendingStatus,
            @Param("generatingStatus") IllustrationStatus generatingStatus);

    @Modifying
    @Query("DELETE FROM BookCoverEntity c WHERE c.book.id = :bookId")
    void deleteByBookId(@Param("bookId") String bookId);
}
