package com.classicchatreader.repository;

import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.service.SearchService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParagraphRepository extends JpaRepository<ParagraphEntity, String> {

    List<ParagraphEntity> findByChapterIdOrderByParagraphIndex(String chapterId);

    /** Every paragraph of a book in reading order, as plain values: one query for the search rebuild. */
    @Query("SELECT new com.classicchatreader.service.SearchService$ParagraphText(p.chapter.id, p.paragraphIndex, p.content) "
            + "FROM ParagraphEntity p WHERE p.chapter.book.id = :bookId "
            + "ORDER BY p.chapter.chapterIndex, p.paragraphIndex")
    List<SearchService.ParagraphText> findTextByBookId(@Param("bookId") String bookId);

    Optional<ParagraphEntity> findByChapterIdAndParagraphIndex(String chapterId, int paragraphIndex);

    boolean existsByChapterIdAndParagraphIndex(String chapterId, int paragraphIndex);
}
