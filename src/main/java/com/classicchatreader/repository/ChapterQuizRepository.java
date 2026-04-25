package com.classicchatreader.repository;

import com.classicchatreader.entity.ChapterQuizEntity;
import com.classicchatreader.entity.ChapterQuizStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChapterQuizRepository extends JpaRepository<ChapterQuizEntity, String> {

    Optional<ChapterQuizEntity> findByChapterId(String chapterId);

    List<ChapterQuizEntity> findByChapterBookIdAndStatus(String bookId, ChapterQuizStatus status);

    List<ChapterQuizEntity> findByChapterBookIdAndStatusIsNull(String bookId);

    @Query("SELECT cq FROM ChapterQuizEntity cq JOIN FETCH cq.chapter c JOIN FETCH c.book WHERE c.id = :chapterId")
    Optional<ChapterQuizEntity> findByChapterIdWithChapterAndBook(@Param("chapterId") String chapterId);

    @Modifying
    @Query("DELETE FROM ChapterQuizEntity cq WHERE cq.chapter.book.id = :bookId")
    void deleteByBookId(@Param("bookId") String bookId);
}
