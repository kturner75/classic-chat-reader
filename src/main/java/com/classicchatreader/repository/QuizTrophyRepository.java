package com.classicchatreader.repository;

import com.classicchatreader.entity.QuizTrophyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuizTrophyRepository extends JpaRepository<QuizTrophyEntity, String> {

    List<QuizTrophyEntity> findByReaderIdAndUserIdIsNull(String readerId);

    Optional<QuizTrophyEntity> findByBookIdAndReaderIdAndCode(String bookId, String readerId, String code);

    List<QuizTrophyEntity> findByBookIdAndReaderIdOrderByUnlockedAtDesc(String bookId, String readerId);

    Optional<QuizTrophyEntity> findByBookIdAndUserIdAndCode(String bookId, String userId, String code);

    List<QuizTrophyEntity> findByBookIdAndUserIdOrderByUnlockedAtDesc(String bookId, String userId);

    Optional<QuizTrophyEntity> findByBookIdAndCode(String bookId, String code);

    List<QuizTrophyEntity> findByBookIdOrderByUnlockedAtDesc(String bookId);

    @Modifying
    @Query("DELETE FROM QuizTrophyEntity qt WHERE qt.book.id = :bookId")
    void deleteByBookId(@Param("bookId") String bookId);
}
