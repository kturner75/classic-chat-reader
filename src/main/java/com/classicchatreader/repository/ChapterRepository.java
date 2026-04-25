package com.classicchatreader.repository;

import com.classicchatreader.entity.ChapterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChapterRepository extends JpaRepository<ChapterEntity, String> {

    List<ChapterEntity> findByBookIdOrderByChapterIndex(String bookId);

    Optional<ChapterEntity> findByBookIdAndChapterIndex(String bookId, int chapterIndex);

    @Query("SELECT c FROM ChapterEntity c JOIN FETCH c.book WHERE c.id = :id")
    Optional<ChapterEntity> findByIdWithBook(@Param("id") String id);
}
