package com.classicchatreader.repository;

import com.classicchatreader.entity.AssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssignmentRepository extends JpaRepository<AssignmentEntity, String> {
    List<AssignmentEntity> findByTermIdAndStatusAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
            String termId, String status);
    List<AssignmentEntity> findByTermIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(String termId);
    Optional<AssignmentEntity> findByIdAndDeletedAtIsNull(String id);
    boolean existsByTermIdAndCharacterChatRequiredTrueAndDeletedAtIsNull(String termId);
}
