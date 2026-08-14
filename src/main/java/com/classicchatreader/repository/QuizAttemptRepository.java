package com.classicchatreader.repository;

import com.classicchatreader.entity.QuizAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuizAttemptRepository extends JpaRepository<QuizAttemptEntity, String> {

    List<QuizAttemptEntity> findByReaderIdAndUserIdIsNull(String readerId);

    long countByChapterBookIdAndReaderId(String bookId, String readerId);

    long countByChapterBookIdAndReaderIdAndPerfectTrue(String bookId, String readerId);

    List<QuizAttemptEntity> findByChapterBookIdAndReaderIdOrderByCreatedAtDesc(String bookId, String readerId);

    long countByChapterBookIdAndUserId(String bookId, String userId);

    long countByChapterBookIdAndUserIdAndPerfectTrue(String bookId, String userId);

    List<QuizAttemptEntity> findByChapterBookIdAndUserIdOrderByCreatedAtDesc(String bookId, String userId);

    long countByChapterBookId(String bookId);

    long countByChapterBookIdAndPerfectTrue(String bookId);

    List<QuizAttemptEntity> findByChapterBookIdOrderByCreatedAtDesc(String bookId);

    boolean existsByChapterId(String chapterId);

    boolean existsByChapterIdAndUserId(String chapterId, String userId);

    long countByChapterIdAndUserId(String chapterId, String userId);

    List<QuizAttemptEntity> findByChapterIdAndUserIdOrderByCreatedAtDesc(String chapterId, String userId);

    @Query("""
            SELECT COALESCE(MAX(qa.correctAnswers), 0)
            FROM QuizAttemptEntity qa
            WHERE qa.chapter.id = :chapterId AND qa.userId = :userId
            """)
    int findMaxCorrectAnswersByChapterIdAndUserId(
            @Param("chapterId") String chapterId,
            @Param("userId") String userId);

    @Query("""
            SELECT COUNT(qa)
            FROM QuizAttemptEntity qa
            WHERE qa.assignmentId = :assignmentId
              AND qa.userId = :userId
              AND qa.createdAt >= :since
            """)
    long countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
            @Param("assignmentId") String assignmentId,
            @Param("userId") String userId,
            @Param("since") java.time.LocalDateTime since);

    @Query("""
            SELECT COALESCE(MAX(qa.correctAnswers), 0)
            FROM QuizAttemptEntity qa
            WHERE qa.assignmentId = :assignmentId
              AND qa.userId = :userId
              AND qa.createdAt >= :since
            """)
    int findMaxCorrectAnswersByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
            @Param("assignmentId") String assignmentId,
            @Param("userId") String userId,
            @Param("since") java.time.LocalDateTime since);

    @Query("""
            SELECT COALESCE(MAX(qa.scorePercent), 0)
            FROM QuizAttemptEntity qa
            WHERE qa.assignmentId = :assignmentId
              AND qa.userId = :userId
              AND qa.createdAt >= :since
            """)
    int findMaxScorePercentByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
            @Param("assignmentId") String assignmentId,
            @Param("userId") String userId,
            @Param("since") java.time.LocalDateTime since);

    boolean existsByAssignmentIdAndUserId(String assignmentId, String userId);

    List<QuizAttemptEntity> findByAssignmentIdAndUserIdOrderByCreatedAtDesc(String assignmentId, String userId);

    @Query("""
            SELECT COALESCE(MAX(qa.scorePercent), 0)
            FROM QuizAttemptEntity qa
            WHERE qa.chapter.id = :chapterId
              AND qa.userId = :userId
              AND qa.createdAt >= :since
            """)
    int findMaxScorePercentByChapterIdAndUserIdAndCreatedAtOnOrAfter(
            @Param("chapterId") String chapterId,
            @Param("userId") String userId,
            @Param("since") java.time.LocalDateTime since);

    @Query("""
            SELECT COUNT(qa)
            FROM QuizAttemptEntity qa
            WHERE qa.chapter.id = :chapterId
              AND qa.userId = :userId
              AND qa.createdAt >= :since
            """)
    long countByChapterIdAndUserIdAndCreatedAtOnOrAfter(
            @Param("chapterId") String chapterId,
            @Param("userId") String userId,
            @Param("since") java.time.LocalDateTime since);

    @Query("""
            SELECT COALESCE(MAX(qa.correctAnswers), 0)
            FROM QuizAttemptEntity qa
            WHERE qa.chapter.id = :chapterId
              AND qa.userId = :userId
              AND qa.createdAt >= :since
            """)
    int findMaxCorrectAnswersByChapterIdAndUserIdAndCreatedAtOnOrAfter(
            @Param("chapterId") String chapterId,
            @Param("userId") String userId,
            @Param("since") java.time.LocalDateTime since);

    @Query("""
            SELECT COUNT(qa)
            FROM QuizAttemptEntity qa
            WHERE qa.chapter.id = :chapterId
              AND qa.userId = :userId
              AND qa.createdAt >= :since
              AND (qa.assignmentId IS NULL OR qa.assignmentId <> :assignmentId)
            """)
    long countByChapterIdAndUserIdAndCreatedAtOnOrAfterExcludingAssignment(
            @Param("chapterId") String chapterId,
            @Param("userId") String userId,
            @Param("since") java.time.LocalDateTime since,
            @Param("assignmentId") String assignmentId);

    @Query("""
            SELECT COALESCE(MAX(qa.scorePercent), 0)
            FROM QuizAttemptEntity qa
            WHERE qa.chapter.id = :chapterId
              AND qa.userId = :userId
              AND qa.createdAt >= :since
              AND (qa.assignmentId IS NULL OR qa.assignmentId <> :assignmentId)
            """)
    int findMaxScorePercentByChapterIdAndUserIdAndCreatedAtOnOrAfterExcludingAssignment(
            @Param("chapterId") String chapterId,
            @Param("userId") String userId,
            @Param("since") java.time.LocalDateTime since,
            @Param("assignmentId") String assignmentId);

    @Query("""
            SELECT COALESCE(MAX(qa.correctAnswers), 0)
            FROM QuizAttemptEntity qa
            WHERE qa.chapter.id = :chapterId
              AND qa.userId = :userId
              AND qa.createdAt >= :since
              AND (qa.assignmentId IS NULL OR qa.assignmentId <> :assignmentId)
            """)
    int findMaxCorrectAnswersByChapterIdAndUserIdAndCreatedAtOnOrAfterExcludingAssignment(
            @Param("chapterId") String chapterId,
            @Param("userId") String userId,
            @Param("since") java.time.LocalDateTime since,
            @Param("assignmentId") String assignmentId);

    /** Classroom assignment COMPLETE: require a perfect (100%) attempt for the user. */
    boolean existsByChapterIdAndUserIdAndPerfectTrue(String chapterId, String userId);

    boolean existsByChapterIdAndPerfectTrue(String chapterId);

    @Modifying
    @Query("DELETE FROM QuizAttemptEntity qa WHERE qa.chapter.book.id = :bookId")
    void deleteByBookId(@Param("bookId") String bookId);
}
