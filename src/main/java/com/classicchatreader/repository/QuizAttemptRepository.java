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

    @Modifying
    @Query("DELETE FROM QuizAttemptEntity qa WHERE qa.chapter.book.id = :bookId")
    void deleteByBookId(@Param("bookId") String bookId);
}
