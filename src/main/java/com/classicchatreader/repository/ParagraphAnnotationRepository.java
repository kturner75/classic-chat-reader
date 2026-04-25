package com.classicchatreader.repository;

import com.classicchatreader.entity.ParagraphAnnotationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParagraphAnnotationRepository extends JpaRepository<ParagraphAnnotationEntity, String> {

    List<ParagraphAnnotationEntity> findByReaderIdAndUserIdIsNull(String readerId);

    List<ParagraphAnnotationEntity> findByUserIdAndBook_Id(String userId, String bookId);

    List<ParagraphAnnotationEntity> findByUserIdAndBook_IdAndBookmarkedTrueOrderByUpdatedAtDesc(
            String userId,
            String bookId
    );

    Optional<ParagraphAnnotationEntity> findByUserIdAndBook_IdAndChapter_IdAndParagraphIndex(
            String userId,
            String bookId,
            String chapterId,
            int paragraphIndex
    );

    List<ParagraphAnnotationEntity> findByReaderIdAndBook_Id(String readerId, String bookId);

    List<ParagraphAnnotationEntity> findByReaderIdAndBook_IdAndBookmarkedTrueOrderByUpdatedAtDesc(
            String readerId,
            String bookId
    );

    Optional<ParagraphAnnotationEntity> findByReaderIdAndBook_IdAndChapter_IdAndParagraphIndex(
            String readerId,
            String bookId,
            String chapterId,
            int paragraphIndex
    );
}
