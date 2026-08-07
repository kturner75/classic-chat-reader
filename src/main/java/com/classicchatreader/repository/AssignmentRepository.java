package com.classicchatreader.repository;

import com.classicchatreader.entity.AssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssignmentRepository extends JpaRepository<AssignmentEntity, String> {
    List<AssignmentEntity> findByTermIdAndStatusAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
            String termId, String status);
    List<AssignmentEntity> findByTermIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(String termId);
    Optional<AssignmentEntity> findByIdAndDeletedAtIsNull(String id);
    boolean existsByTermIdAndCharacterChatRequiredTrueAndDeletedAtIsNull(String termId);

    List<AssignmentEntity> findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(
            String chapterId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a FROM AssignmentEntity a
            WHERE a.chapterId = :chapterId
              AND a.quizRequired = true
              AND a.status = :status
              AND a.deletedAt IS NULL
            """)
    List<AssignmentEntity> findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNullForUpdate(
            @Param("chapterId") String chapterId,
            @Param("status") String status);
}
