package com.classicchatreader.repository;

import com.classicchatreader.entity.AssignmentEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssignmentRepository extends JpaRepository<AssignmentEntity, String> {
    @EntityGraph(attributePaths = "chapters")
    List<AssignmentEntity> findByTermIdAndStatusAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
            String termId, String status);

    @EntityGraph(attributePaths = "chapters")
    List<AssignmentEntity> findByTermIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(String termId);

    @EntityGraph(attributePaths = "chapters")
    Optional<AssignmentEntity> findByIdAndDeletedAtIsNull(String id);

    boolean existsByTermIdAndCharacterChatRequiredTrueAndDeletedAtIsNull(String termId);

    @Query("""
            SELECT DISTINCT a FROM AssignmentEntity a
            JOIN a.chapters c
            WHERE c.chapterId = :chapterId
              AND a.quizRequired = true
              AND a.status = :status
              AND a.deletedAt IS NULL
            """)
    List<AssignmentEntity> findByContainedChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
            @Param("chapterId") String chapterId,
            @Param("status") String status);

    @Query("""
            SELECT DISTINCT a FROM AssignmentEntity a
            JOIN a.chapters c
            WHERE a.termId = :termId
              AND c.chapterId = :chapterId
              AND a.quizRequired = true
              AND a.status = :status
              AND a.deletedAt IS NULL
            """)
    List<AssignmentEntity> findByTermIdAndContainedChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
            @Param("termId") String termId,
            @Param("chapterId") String chapterId,
            @Param("status") String status);
}
