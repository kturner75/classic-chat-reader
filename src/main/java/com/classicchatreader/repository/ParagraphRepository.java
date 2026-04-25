package com.classicchatreader.repository;

import com.classicchatreader.entity.ParagraphEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParagraphRepository extends JpaRepository<ParagraphEntity, String> {

    List<ParagraphEntity> findByChapterIdOrderByParagraphIndex(String chapterId);

    Optional<ParagraphEntity> findByChapterIdAndParagraphIndex(String chapterId, int paragraphIndex);

    boolean existsByChapterIdAndParagraphIndex(String chapterId, int paragraphIndex);
}
