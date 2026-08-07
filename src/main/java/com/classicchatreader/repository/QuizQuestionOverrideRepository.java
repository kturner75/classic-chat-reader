package com.classicchatreader.repository;

import com.classicchatreader.entity.QuizQuestionOverrideEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuizQuestionOverrideRepository extends JpaRepository<QuizQuestionOverrideEntity, String> {

    List<QuizQuestionOverrideEntity> findByTermIdAndChapterIdAndStatusAndDeletedAtIsNullOrderBySortOrderAsc(
            String termId, String chapterId, String status);

    List<QuizQuestionOverrideEntity> findByTermIdAndChapterIdAndDeletedAtIsNullOrderBySortOrderAsc(
            String termId, String chapterId);

    boolean existsByTermIdAndChapterIdAndStatusAndDeletedAtIsNull(String termId, String chapterId, String status);
}
